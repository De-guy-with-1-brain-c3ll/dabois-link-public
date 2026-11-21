package link.dabois;

import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;

public final class ServerCommands {
    private static volatile Host host;

    public static void start(DedicatedServer server) {
        Path directory = server.getServerDirectory();
        Path config = directory.resolve(HostSetup.DIRECTORY);
        Path settings = config.resolve("server.properties");
        if (Files.notExists(settings)) {
            return;
        }
        String address = server.getLocalIp();
        try {
            boolean offline = !server.usesAuthentication();
            if (offline && !privateOfflineBackend(address, settings)) {
                System.err.println("DABOIS Link requires a loopback Minecraft listener in offline mode.");
                return;
            }
            host = new Host(config, address.isBlank() ? "127.0.0.1" : address, server.getPort(),
                    name -> offline && operator(directory.resolve("ops.json"), name), server);
            System.out.println("DABOIS Link listening on TCP " + host.port());
        } catch (Exception e) {
            System.err.println("DABOIS Link could not start: " + e.getMessage());
        }
    }

    private static boolean privateOfflineBackend(String address, Path settings) throws IOException {
        if (address.equals("127.0.0.1") || address.equals("::1")) {
            return true;
        }
        if (!address.matches("[0-9.]+") || !InetAddress.getByName(address).isSiteLocalAddress()) {
            return false;
        }
        return Boolean.parseBoolean(Link.read(settings).getProperty("allowPrivateUpstream", "false"));
    }

    private static boolean operator(Path path, String name) {
        // Use the persisted list so privileges changed in game are reflected in pairing.
        try (var reader = Files.newBufferedReader(path)) {
            for (var entry : JsonParser.parseReader(reader).getAsJsonArray()) {
                if (entry.getAsJsonObject().get("name").getAsString().equalsIgnoreCase(name)) {
                    return true;
                }
            }
            return false;
        } catch (java.nio.file.NoSuchFileException e) {
            return false;
        } catch (IOException | RuntimeException e) {
            return true;
        }
    }

    public static void stop() {
        Host current = host;
        host = null;
        if (current != null) {
            current.close();
        }
    }

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("dablink")
                .requires(source -> source.getEntity() == null && source.getServer().isDedicatedServer()
                        && Commands.<CommandSourceStack>hasPermission(Commands.LEVEL_OWNERS).test(source))
                .then(Commands.literal("code").executes(c -> run(c.getSource(), invitations ->
                        "Shared server code: " + invitations.code() + " (valid for up to 15 minutes).")))
                .then(Commands.literal("rotate").executes(c -> run(c.getSource(), invitations ->
                        "New shared server code: " + invitations.rotate() + " (valid for 15 minutes).")))
                .then(Commands.literal("approve").then(Commands.argument("player", StringArgumentType.word())
                        .then(Commands.argument("device", StringArgumentType.word()).executes(c ->
                                run(c.getSource(), invitations -> {
                                    String name = StringArgumentType.getString(c, "player");
                                    invitations.approve(name, StringArgumentType.getString(c, "device"));
                                    return "Approved this device for " + name + ".";
                                })))))
                .then(Commands.literal("revoke").then(Commands.argument("player", StringArgumentType.word())
                        .executes(c -> run(c.getSource(), invitations -> {
                            String name = StringArgumentType.getString(c, "player");
                            host.revoke(name);
                            return "Revoked pairing for " + name + ".";
                        })))));
    }

    private static int run(CommandSourceStack source, Action action) {
        Host current = host;
        if (current == null) {
            source.sendFailure(Component.literal("DABOIS Link is unavailable. Check the startup log."));
            return 0;
        }
        try {
            String result = action.run(current.invitations);
            source.sendSuccess(() -> Component.literal(result), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal(e.getMessage()));
            return 0;
        }
    }

    private interface Action {
        String run(Invitations invitations) throws IOException;
    }
}
