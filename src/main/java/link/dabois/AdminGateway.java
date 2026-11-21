package link.dabois;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import net.minecraft.network.chat.Component;
import net.minecraft.server.dedicated.DedicatedServer;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.permissions.PermissionLevel;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserBanListEntry;
import net.minecraft.world.Difficulty;

final class AdminGateway {
    private static final Gson JSON = new Gson();
    private static final String LINK_JAR = "dabois-link.jar";
    private static final Set<String> MANAGED_BY_HOST = Set.of(
            "server-ip", "enable-rcon", "rcon.password", "rcon.port",
            "management-server-enabled", "management-server-host", "management-server-port",
            "management-server-secret", "management-server-tls-keystore", "management-server-tls-keystore-password");
    private static final Set<String> DISTANCES = Set.of("view-distance", "simulation-distance");

    private final DedicatedServer server;
    private final Path directory;
    private final Path config;
    private final Path stagedFile;
    private final Path applyFile;
    private final Invitations invitations;

    AdminGateway(DedicatedServer server, Path config, Invitations invitations) {
        this.server = server;
        this.config = config;
        this.invitations = invitations;
        directory = server.getServerDirectory();
        stagedFile = config.resolve("staged.properties");
        applyFile = config.resolve("apply-request.properties");
    }

    void respond(DataInputStream in, DataOutputStream out) throws IOException {
        String token = in.readUTF();
        String name = invitations.authenticate(token);
        if (name == null || !name.equals(admins().getProperty(Invitations.fingerprint(token)))) {
            send(out, false, "This device does not have Super Operator access.");
            return;
        }
        String payload = in.readUTF();
        CompletableFuture<String> result = new CompletableFuture<>();
        server.execute(() -> {
            if (result.isDone()) {
                return;
            }
            try {
                if (!name.equals(invitations.authenticate(token))
                        || !name.equals(admins().getProperty(Invitations.fingerprint(token)))) {
                    throw new IOException("This device no longer has Super Operator access.");
                }
                result.complete(JSON.toJson(handle(name, JsonParser.parseString(payload).getAsJsonObject())));
            } catch (Exception e) {
                result.completeExceptionally(e);
            }
        });
        try {
            send(out, true, result.get(5, TimeUnit.SECONDS));
        } catch (TimeoutException e) {
            result.cancel(false);
            send(out, false, "The Minecraft server is busy. Refresh to check the current state.");
        } catch (ExecutionException e) {
            String message = e.getCause().getMessage();
            send(out, false, message == null ? "Request failed." : message);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Server is stopping.", e);
        }
    }

    private static void send(DataOutputStream out, boolean ok, String response) throws IOException {
        if (response.length() > 20_000) {
            out.writeBoolean(false);
            out.writeUTF("The server returned too much data for this panel.");
            return;
        }
        out.writeBoolean(ok);
        out.writeUTF(response);
    }

    private Object handle(String admin, JsonObject request) throws IOException {
        String action = field(request, "action");
        switch (action) {
            case "snapshot" -> { return snapshot(admin); }
            case "difficulty" -> {
                Difficulty difficulty = Difficulty.byName(field(request, "difficulty"));
                if (difficulty == null) {
                    throw new IOException("Unknown difficulty.");
                }
                if (server.isHardcore() && difficulty != Difficulty.HARD) {
                    throw new IOException("Hardcore worlds use Hard difficulty.");
                }
                server.setDifficulty(difficulty, true);
            }
            case "role" -> role(admin, player(request), field(request, "role"));
            case "ban" -> ban(admin, player(request), field(request, "reason"));
            case "pardon" -> pardon(player(request));
            case "settings" -> stageSettings(request.getAsJsonObject("settings"));
            case "world" -> stageWorld(field(request, "world"), field(request, "seed"), request.get("create").getAsBoolean());
            case "mod" -> stageMod(field(request, "mod"), request.get("enabled").getAsBoolean());
            case "discard" -> {
                editable();
                Files.deleteIfExists(stagedFile);
            }
            case "apply" -> {
                editable();
                if (Files.notExists(config.resolve("restart.enabled"))) {
                    throw new IOException("The host has not enabled dashboard restarts.");
                }
                if (Files.notExists(stagedFile) || staged().isEmpty()) {
                    throw new IOException("There are no staged changes.");
                }
                Properties flag = new Properties();
                flag.setProperty("requestedBy", admin);
                Link.save(applyFile, flag);
                return Map.of("restarting", true);
            }
            default -> throw new IOException("Unknown dashboard action.");
        }
        return snapshot(admin);
    }

    private Map<String, Object> snapshot(String admin) throws IOException {
        Properties live = Link.read(directory.resolve("server.properties"));
        Properties staged = staged();
        Map<String, String> settings = new TreeMap<>();
        for (String key : live.stringPropertyNames()) {
            if (!MANAGED_BY_HOST.contains(key)) {
                settings.put(key, staged.getProperty("setting." + key, live.getProperty(key)));
            }
        }
        List<String> worlds;
        try (var paths = Files.list(directory)) {
            worlds = paths.filter(path -> Files.isRegularFile(path.resolve("level.dat")))
                    .map(path -> path.getFileName().toString()).sorted().toList();
        }
        Map<String, Boolean> mods = new TreeMap<>();
        scanMods(mods, "mods", true);
        scanMods(mods, "mods-disabled", false);
        staged.stringPropertyNames().stream().filter(key -> key.startsWith("mod."))
                .forEach(key -> mods.put(key.substring(4), Boolean.parseBoolean(staged.getProperty(key))));
        Set<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        names.addAll(invitations.names());
        names.addAll(Arrays.asList(server.getPlayerList().getOps().getUserList()));
        names.addAll(Arrays.asList(server.getPlayerList().getBans().getUserList()));
        server.getPlayerList().getPlayers().forEach(player -> names.add(player.getGameProfile().name()));
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("admin", admin);
        snapshot.put("superoperators", new TreeSet<>(admins().values().stream().map(Object::toString).toList()));
        snapshot.put("operators", server.getPlayerList().getOps().getUserList());
        snapshot.put("bans", server.getPlayerList().getBans().getUserList());
        snapshot.put("players", names);
        snapshot.put("worlds", worlds);
        snapshot.put("liveWorld", live.getProperty("level-name"));
        snapshot.put("difficulty", server.getWorldData().getDifficulty().getSerializedName());
        snapshot.put("settings", settings);
        snapshot.put("mods", mods);
        snapshot.put("staged", !staged.isEmpty());
        snapshot.put("applying", Files.exists(applyFile));
        snapshot.put("canApply", Files.exists(config.resolve("restart.enabled")));
        Path status = config.resolve("apply-status.properties");
        snapshot.put("applyStatus", Files.exists(status) ? Link.read(status).getProperty("message", "") : "");
        return snapshot;
    }

    private Properties admins() throws IOException {
        Path path = config.resolve("superoperators.properties");
        return Files.exists(path) ? Link.read(path) : new Properties();
    }

    private Properties staged() throws IOException {
        return Files.exists(stagedFile) ? Link.read(stagedFile) : new Properties();
    }

    private void editable() throws IOException {
        if (Files.exists(applyFile)) {
            throw new IOException("An apply is already in progress.");
        }
    }

    private void role(String admin, String name, String role) throws IOException {
        var players = server.getPlayerList();
        Properties admins = admins();
        if (role.equals("superop")) {
            String hash = invitations.deviceHash(name);
            if (hash == null) {
                throw new IOException("This player must pair their device first.");
            }
            admins.setProperty(hash, name);
            Link.save(config.resolve("superoperators.properties"), admins);
            return;
        }
        if (role.equals("remove-superop")) {
            if (admin.equalsIgnoreCase(name)) {
                throw new IOException("Ask another Super Operator to remove your dashboard access.");
            }
            admins.values().removeIf(value -> name.equalsIgnoreCase(value.toString()));
            Link.save(config.resolve("superoperators.properties"), admins);
            return;
        }
        if (!role.matches("[0-4]")) {
            throw new IOException("Unknown Minecraft permission level.");
        }
        NameAndId profile = resolveProfile(name);
        int level = Integer.parseInt(role);
        if (level == 0) {
            players.deop(profile);
        } else {
            players.op(profile, Optional.of(LevelBasedPermissionSet.forLevel(PermissionLevel.byId(level))),
                    Optional.empty());
        }
    }

    private void ban(String admin, String name, String reason) throws IOException {
        if (admin.equalsIgnoreCase(name) || admins().values().stream().anyMatch(v -> name.equalsIgnoreCase(v.toString()))) {
            throw new IOException("Remove dashboard access before banning a Super Operator.");
        }
        if (reason.length() > 256) {
            throw new IOException("Use a ban reason under 256 characters.");
        }
        NameAndId profile = resolveProfile(name);
        server.getPlayerList().getBans().add(new UserBanListEntry(profile, new Date(), admin, null, reason));
        var online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            online.connection.disconnect(Component.literal(reason));
        }
    }

    private void pardon(String name) throws IOException {
        var bans = server.getPlayerList().getBans();
        for (var entry : List.copyOf(bans.getEntries())) {
            if (entry.getUser().name().equalsIgnoreCase(name)) {
                bans.remove(entry);
            }
        }
    }

    private NameAndId resolveProfile(String name) throws IOException {
        var online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return new NameAndId(online.getGameProfile());
        }
        for (var entry : server.getPlayerList().getOps().getEntries()) {
            if (entry.getUser().name().equalsIgnoreCase(name)) {
                return entry.getUser();
            }
        }
        if (server.usesAuthentication()) {
            throw new IOException("For an online-mode server, the player must be connected or already an operator.");
        }
        UUID offline = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
        return new NameAndId(offline, name);
    }

    private void stageSettings(JsonObject updates) throws IOException {
        editable();
        Properties live = Link.read(directory.resolve("server.properties"));
        Properties staged = staged();
        for (var item : updates.entrySet()) {
            String key = item.getKey();
            String value = item.getValue().getAsString();
            String existing = live.getProperty(key);
            if (existing == null || MANAGED_BY_HOST.contains(key) || key.equals("level-name") || key.equals("level-seed")) {
                throw new IOException("Use the host or Worlds panel to change " + key + ".");
            }
            if (value.length() > 2000 || value.indexOf('\0') >= 0) {
                throw new IOException("Invalid value for " + key + ".");
            }
            if ((existing.equals("true") || existing.equals("false")) && !value.matches("true|false")) {
                throw new IOException(key + " must be true or false.");
            }
            if (existing.matches("-?[0-9]+") && !value.matches("-?[0-9]+")) {
                throw new IOException(key + " must be a whole number.");
            }
            if (DISTANCES.contains(key) && !value.matches("[3-9]|[12][0-9]|3[0-2]")) {
                throw new IOException(key + " must be between 3 and 32.");
            }
            putChange(staged, "setting." + key, value, existing);
        }
        Link.save(stagedFile, staged);
    }

    private void stageWorld(String world, String seed, boolean create) throws IOException {
        editable();
        if (!world.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,39}") || seed.length() > 128) {
            throw new IOException("Use a world name of up to 40 letters, numbers, underscores or hyphens.");
        }
        Path target = directory.resolve(world);
        if (Files.isSymbolicLink(target) || (create ? Files.exists(target) : !Files.isRegularFile(target.resolve("level.dat")))) {
            throw new IOException(create ? "That directory already exists." : "That world does not exist.");
        }
        Properties staged = staged();
        staged.setProperty("setting.level-name", world);
        staged.setProperty("setting.level-seed", create ? seed : "");
        Link.save(stagedFile, staged);
    }

    private void stageMod(String name, boolean enabled) throws IOException {
        editable();
        if (!name.matches("[A-Za-z0-9][A-Za-z0-9_.+ -]{0,149}\\.jar") || name.equalsIgnoreCase(LINK_JAR)) {
            throw new IOException("Only installed mod JARs can be changed here.");
        }
        boolean active = Files.isRegularFile(directory.resolve("mods").resolve(name), LinkOption.NOFOLLOW_LINKS);
        boolean disabled = Files.isRegularFile(directory.resolve("mods-disabled").resolve(name), LinkOption.NOFOLLOW_LINKS);
        if (active == disabled) {
            throw new IOException("Could not identify a unique installed JAR.");
        }
        Properties staged = staged();
        putChange(staged, "mod." + name, Boolean.toString(enabled), Boolean.toString(active));
        Link.save(stagedFile, staged);
    }

    private void scanMods(Map<String, Boolean> mods, String folder, boolean enabled) throws IOException {
        Path path = directory.resolve(folder);
        if (Files.isDirectory(path)) {
            try (var files = Files.list(path)) {
                files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                        .map(file -> file.getFileName().toString()).filter(name -> name.endsWith(".jar"))
                        .filter(name -> !name.equalsIgnoreCase(LINK_JAR)).forEach(name -> mods.put(name, enabled));
            }
        }
    }

    private static void putChange(Properties staged, String key, String pending, String original) {
        if (pending.equals(original)) {
            staged.remove(key);
        } else {
            staged.setProperty(key, pending);
        }
    }

    private static String player(JsonObject request) throws IOException {
        String name = field(request, "player");
        if (!Invitations.validName(name)) {
            throw new IOException("Enter the exact Minecraft profile name.");
        }
        return name;
    }

    private static String field(JsonObject request, String key) throws IOException {
        if (!request.has(key)) {
            throw new IOException("Missing " + key + ".");
        }
        return request.get(key).getAsString();
    }
}
