import com.google.gson.*;
import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.ExecutionException;
import java.util.jar.JarFile;

public final class Setup {
    private static final String GAME = "26.3";
    private static final String LOADER = "0.20.1";
    private static final String PROFILE = "DABOIS-26.3-Fabric";
    private static final String FABRIC = "https://meta.fabricmc.net/v2/versions/";
    private static final String INVITE = "dabois-link-invite.properties";
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    private final JFrame frame = new JFrame("DABOIS Setup");
    private final JLabel progress = new JLabel(" ");
    private final JButton install = new JButton("Install");
    private final JButton open = new JButton("Open folder");
    private final JButton start = new JButton("Start server");
    private final JButton launch = new JButton("Open HMCL");
    private final JTextField location = new JTextField(30);
    private final JTextField connection = new JTextField(30);
    private final JTextField port = new JTextField("25576", 6);
    private final JCheckBox agreement = new JCheckBox("I accept the Minecraft server EULA.");
    private final JComboBox<String> clientMode = new JComboBox<>(new String[]{
            "Use my existing launcher", "Install a fresh copy of HMCL"});
    private final boolean server;
    private Path installed;

    private Setup(boolean server) {
        this.server = server;
        Path home = Path.of(System.getProperty("user.home"));
        location.setText((server ? home.resolve("DABOIS Server") : defaultGame(home)).toString());
        connection.setText(server ? "localhost" : "");
        var form = new JPanel();
        form.setBorder(BorderFactory.createEmptyBorder(20, 22, 20, 22));
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        var title = new JLabel(server ? "Host a DABOIS server" : "Play on a DABOIS server");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 22f));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(title);
        form.add(Box.createVerticalStrut(10));
        addText(form, server
                ? "Create a new Minecraft 26.3 server with Fabric, DABOIS Link and voice chat."
                : "Set up Minecraft 26.3 with Fabric, DABOIS Link and voice chat.");
        if (!server) {
            var existing = defaultGame(home);
            if (!Files.isDirectory(existing.resolve("versions")) && officialProfiles(existing) == null) {
                clientMode.setSelectedIndex(1);
                location.setText(home.resolve("DABOIS Launcher").toString());
            }
            clientMode.setAlignmentX(Component.LEFT_ALIGNMENT);
            clientMode.setMaximumSize(new Dimension(Integer.MAX_VALUE, clientMode.getPreferredSize().height));
            form.add(clientMode);
            clientMode.addActionListener(e -> location.setText((clientMode.getSelectedIndex() == 1
                    ? home.resolve("DABOIS Launcher") : existing).toString()));
        }
        addField(form, server ? "New server folder" : "Launcher or game folder", location, () -> choose(location, true));
        addField(form, server ? "Address players can reach" : "DABOIS invite JAR from your host",
                connection, server ? null : () -> choose(connection, false));
        if (server) {
            addField(form, "Public connection port", port, null);
            addText(form, "Use localhost for a test on this computer. For remote players, enter your reachable host or relay.");
            agreement.setAlignmentX(Component.LEFT_ALIGNMENT);
            form.add(agreement);
            var eula = new JButton("Read the Minecraft EULA");
            eula.setAlignmentX(Component.LEFT_ALIGNMENT);
            eula.addActionListener(e -> reveal(URI.create("https://www.minecraft.net/eula")));
            form.add(eula);
        } else {
            addText(form, "Setup detects HMCL and the official Minecraft Launcher. For a fresh HMCL install, choose a new folder.");
            if (bundledInvite()) addText(form, "Your DABOIS server invite is included. Leave the invite field empty to use it.");
        }
        form.add(Box.createVerticalStrut(12));
        progress.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(progress);
        var buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
        buttons.setAlignmentX(Component.LEFT_ALIGNMENT);
        buttons.add(install);
        buttons.add(open);
        if (server) buttons.add(start);
        else buttons.add(launch);
        form.add(buttons);
        open.setEnabled(false);
        start.setEnabled(false);
        launch.setEnabled(false);
        launch.addActionListener(e -> {
            try {
                process(installed, "-Dhmcl.dir=" + installed.resolve(".hmcl"), "-jar", "HMCL.jar");
            } catch (IOException error) {
                message("HMCL could not open. " + error.getMessage());
            }
        });
        install.addActionListener(e -> install());
        open.addActionListener(e -> reveal(installed.toUri()));
        start.addActionListener(e -> console());
        frame.setContentPane(form);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    public static void main(String[] args) {
        if (args.length == 1 && args[0].equals("--verify")) {
            for (String file : new String[]{"server-mod.jar", "voice.jar", "hmcl.jar"}) {
                if (Setup.class.getResource("/payload/" + file) == null) throw new IllegalStateException("Missing " + file);
            }
            System.out.println("Setup bundle verified.");
            return;
        }
        if (args.length == 2 && args[0].equals("manage")) {
            Path folder = Path.of(args[1]).toAbsolutePath().normalize();
            SwingUtilities.invokeLater(() -> {
                var ui = new Setup(true);
                if (!Files.isRegularFile(folder.resolve("fabric-server-launch.jar"))) {
                    ui.message("Choose a folder created by Host DABOIS.");
                    return;
                }
                ui.installed = folder;
                ui.location.setText(folder.toString());
                ui.progress.setText("Server installed. Use the console to manage it.");
                ui.start.setEnabled(true);
                ui.open.setEnabled(true);
                ui.console();
            });
            return;
        }
        SwingUtilities.invokeLater(() -> new Setup(args.length == 1 && args[0].equals("server")));
    }

    private static Path defaultGame(Path home) {
        Path mac = home.resolve("Library/Application Support/minecraft");
        String appData = System.getenv("APPDATA");
        for (Path path : new Path[]{home.resolve("Desktop/.minecraft"), home.resolve(".minecraft"),
                mac, appData == null ? home.resolve(".minecraft") : Path.of(appData, ".minecraft")}) {
            if (officialProfiles(path) != null || Files.isDirectory(path.resolve("versions"))) return path;
        }
        return System.getProperty("os.name").startsWith("Mac") ? mac : home.resolve(".minecraft");
    }

    private static void addText(JPanel pane, String text) {
        var label = new JLabel("<html><div style='width:420px'>" + text + "</div></html>");
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        pane.add(label);
    }

    private static void addField(JPanel pane, String name, JTextField field, Runnable browse) {
        pane.add(Box.createVerticalStrut(8));
        var label = new JLabel(name);
        label.setLabelFor(field);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        pane.add(label);
        var row = new JPanel(new BorderLayout(8, 0));
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, field.getPreferredSize().height + 4));
        row.add(field);
        if (browse != null) {
            var button = new JButton("Browse…");
            button.addActionListener(e -> browse.run());
            row.add(button, BorderLayout.EAST);
        }
        pane.add(row);
    }

    private void choose(JTextField field, boolean directory) {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(directory ? JFileChooser.DIRECTORIES_ONLY : JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(frame) == JFileChooser.APPROVE_OPTION) {
            Path selected = chooser.getSelectedFile().toPath();
            if (field == location && (server || clientMode.getSelectedIndex() == 1)) {
                selected = selected.resolve(server ? "DABOIS Server" : "DABOIS Launcher");
            }
            field.setText(selected.toAbsolutePath().toString());
        }
    }

    private void install() {
        if (location.getText().isBlank() || (connection.getText().isBlank() && (server || !bundledInvite()))
                || (server && !agreement.isSelected())) {
            message(server ? "Choose a server folder, enter an address, and accept the EULA."
                    : "Choose your HMCL folder and the invite JAR your host sent you.");
            return;
        }
        final Path destination;
        final int publicPort;
        try {
            destination = Path.of(location.getText().strip()).toAbsolutePath().normalize();
            publicPort = server ? Integer.parseInt(port.getText().strip()) : 25576;
            if (publicPort < 1 || publicPort > 65535) throw new IllegalArgumentException();
        } catch (IllegalArgumentException e) {
            message("Enter a valid folder and a port from 1 to 65535.");
            return;
        }
        String input = connection.getText().strip();
        boolean fresh = !server && clientMode.getSelectedIndex() == 1;
        install.setEnabled(false);
        progress.setText("Installing… This can take a few minutes.");
        new SwingWorker<Path, Void>() {
            @Override protected Path doInBackground() throws Exception {
                if (server) return installServer(destination, input, publicPort);
                if (!input.isEmpty()) return installPlayer(destination, Path.of(input), fresh);
                Path included = Files.createTempFile("dabois-invite-", ".jar");
                try {
                    payload("client-invite.jar", included);
                    return installPlayer(destination, included, fresh);
                } finally {
                    Files.deleteIfExists(included);
                }
            }
            @Override protected void done() {
                install.setEnabled(true);
                try {
                    installed = get();
                    open.setEnabled(true);
                    start.setEnabled(server);
                    launch.setEnabled(fresh);
                    progress.setText(server ? "Ready. Start the server, then share the friends JAR."
                            : "Ready. Open your launcher and select " + PROFILE + ".");
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (ExecutionException e) {
                    progress.setText("Setup could not finish.");
                    message(e.getCause().getMessage());
                }
            }
        }.execute();
    }

    private static boolean bundledInvite() {
        return Setup.class.getResource("/payload/client-invite.jar") != null;
    }

    private static Path installPlayer(Path destination, Path invite, boolean fresh) throws Exception {
        return fresh ? installFreshClient(destination, invite) : installClient(destination, invite);
    }

    private void message(String text) {
        JOptionPane.showMessageDialog(frame, text, "DABOIS Setup", JOptionPane.INFORMATION_MESSAGE);
    }

    private void reveal(URI uri) {
        try {
            if ("file".equals(uri.getScheme())) Desktop.getDesktop().open(Path.of(uri).toFile());
            else Desktop.getDesktop().browse(uri);
        } catch (Exception e) {
            message(uri.toString());
        }
    }

    private static Path installServer(Path target, String address, int publicPort) throws Exception {
        if (!address.matches("[A-Za-z0-9.:-]{1,253}")) throw new IOException("Enter a hostname or IP address.");
        if (Files.exists(target)) throw new IOException("Choose a new folder. Setup leaves existing servers and worlds alone.");
        Path parent = Files.createDirectories(target.getParent());
        Path work = Files.createTempDirectory(parent, ".dabois-server-");
        boolean complete = false;
        try {
            Path mods = Files.createDirectory(work.resolve("mods"));
            payload("server-mod.jar", mods.resolve("dabois-link.jar"));
            payload("voice.jar", mods.resolve("voicechat-fabric.jar"));
            var release = json(FABRIC + "installer").getAsJsonArray().get(0).getAsJsonObject();
            String version = value(release, "version");
            String installer = "https://maven.fabricmc.net/net/fabricmc/fabric-installer/" + version
                    + "/fabric-installer-" + version + ".jar";
            Path fabric = work.resolve("fabric-installer.jar");
            download(installer, text(installer + ".sha1").strip(), fabric);
            run(work, "-jar", "fabric-installer.jar", "server", "-dir", ".", "-mcversion", GAME,
                    "-loader", LOADER, "-downloadMinecraft");
            if (!Files.isRegularFile(work.resolve("fabric-server-launch.jar"))) {
                throw new IOException("Fabric could not prepare the server.");
            }
            Files.delete(fabric);
            Files.writeString(work.resolve("eula.txt"), "eula=true\n");
            Files.writeString(work.resolve("server.properties"),
                    "server-ip=127.0.0.1\nserver-port=25565\nonline-mode=true\nmax-players=4\n");
            Path modConfig = Files.createDirectories(work.resolve("config/dabois-link"));
            Files.writeString(modConfig.resolve("server.properties"), "voicePort=24454\n");
            run(work, "-cp", "mods/dabois-link.jar", "link.dabois.HostSetup",
                    address, Integer.toString(publicPort), "DABOIS", "25576");
            try (var in = Setup.class.getProtectionDomain().getCodeSource().getLocation().openStream()) {
                Files.copy(in, work.resolve("DABOIS-Setup.jar"));
            }
            Files.writeString(work.resolve("Start DABOIS.cmd"), "@echo off\r\ncd /d \"%~dp0\"\r\n\""
                    + java().toString() + "\" -jar DABOIS-Setup.jar manage .\r\n");
            Path shell = work.resolve(System.getProperty("os.name").startsWith("Mac")
                    ? "Start DABOIS.command" : "Start DABOIS.sh");
            Files.writeString(shell, "#!/bin/sh\ncd -- \"$(dirname -- \"$0\")\" || exit 1\nexec '"
                    + java().toString().replace("'", "'\\''") + "' -jar DABOIS-Setup.jar manage .\n");
            shell.toFile().setExecutable(true, true);
            Files.move(work, target);
            complete = true;
            return target;
        } finally {
            if (!complete) {
                System.err.println("Incomplete setup files: " + work);
            }
        }
    }

    private static Path installFreshClient(Path target, Path invite) throws Exception {
        if (Files.exists(target)) throw new IOException("Choose a new folder for the fresh HMCL installation.");
        Path parent = Files.createDirectories(target.getParent());
        Path work = Files.createTempDirectory(parent, ".dabois-hmcl-");
        payload("hmcl.jar", work.resolve("HMCL.jar"));
        Path game = Files.createDirectories(work.resolve(".minecraft/versions")).getParent();
        installClient(game, invite);
        Files.writeString(work.resolve("Open HMCL.cmd"), "@echo off\r\ncd /d \"%~dp0\"\r\n\""
                + java() + "\" \"-Dhmcl.dir=%~dp0.hmcl\" -jar HMCL.jar\r\n");
        Path shell = work.resolve(System.getProperty("os.name").startsWith("Mac") ? "Open HMCL.command" : "Open HMCL.sh");
        Files.writeString(shell, "#!/bin/sh\ncd -- \"$(dirname -- \"$0\")\" || exit 1\nexec '"
                + java().toString().replace("'", "'\\''") + "' \"-Dhmcl.dir=$PWD/.hmcl\" -jar HMCL.jar\n");
        shell.toFile().setExecutable(true, true);
        return Files.move(work, target);
    }

    private static Path officialProfiles(Path root) {
        for (String name : new String[]{"launcher_profiles.json", "launcher_profiles_microsoft_store.json"}) {
            Path config = root.resolve(name);
            if (Files.isRegularFile(config)) return config;
        }
        return null;
    }

    private static Path installClient(Path root, Path invite) throws Exception {
        if (Files.isDirectory(root.resolve(".minecraft/versions"))) root = root.resolve(".minecraft");
        Path launcher = officialProfiles(root);
        if (!Files.isDirectory(root.resolve("versions")) && launcher == null) {
            throw new IOException("Choose the game folder for HMCL or the official Minecraft Launcher.");
        }
        try (JarFile jar = new JarFile(invite.toFile())) {
            if (jar.getJarEntry(INVITE) == null || jar.getJarEntry("fabric.mod.json") == null) {
                throw new IOException("That file is not a DABOIS invite JAR.");
            }
            try (InputStream in = jar.getInputStream(jar.getJarEntry("fabric.mod.json"))) {
                if (!"dabois_link".equals(value(JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8))
                        .getAsJsonObject(), "id"))) throw new IOException("Choose the DABOIS Link JAR from your host.");
            }
        }
        String indexUrl = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
        JsonObject base = null;
        for (var entry : json(indexUrl).getAsJsonObject().getAsJsonArray("versions")) {
            var candidate = entry.getAsJsonObject();
            if (GAME.equals(value(candidate, "id"))) {
                base = candidate;
                break;
            }
        }
        if (base == null) throw new IOException("Minecraft " + GAME + " is unavailable.");
        Path version = Files.createDirectories(root.resolve("versions").resolve(GAME));
        download(value(base, "url"), value(base, "sha1"), version.resolve(GAME + ".json"));
        var baseMetadata = JsonParser.parseString(Files.readString(version.resolve(GAME + ".json"))).getAsJsonObject();
        var client = baseMetadata.getAsJsonObject("downloads").getAsJsonObject("client");
        download(value(client, "url"), value(client, "sha1"), version.resolve(GAME + ".jar"));
        var fabric = json(FABRIC + "loader/" + GAME + "/" + LOADER + "/profile/json").getAsJsonObject();
        if (!GAME.equals(value(fabric, "inheritsFrom"))) throw new IOException("Fabric returned an unexpected profile.");
        for (var library : fabric.getAsJsonArray("libraries")) {
            var lib = library.getAsJsonObject();
            String[] parts = value(lib, "name").split(":");
            if (parts.length != 3 || !value(lib, "name").matches("[A-Za-z0-9._+:-]+")) {
                throw new IOException("Fabric returned an unexpected library.");
            }
            String relative = parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/"
                    + parts[1] + "-" + parts[2] + ".jar";
            String url = value(lib, "url").replaceAll("/+$", "") + "/" + relative;
            download(url, lib.has("sha1") ? value(lib, "sha1") : text(url + ".sha1").strip(),
                    root.resolve("libraries").resolve(relative));
        }
        Path profile = root.resolve("versions").resolve(PROFILE);
        Path mods = Files.createDirectories(profile.resolve("mods"));
        Files.copy(invite, mods.resolve("DABOIS-Link.jar"), StandardCopyOption.REPLACE_EXISTING);
        payload("voice.jar", mods.resolve("voicechat-fabric-2.6.27+26.3.jar"));
        Path config = Files.createDirectories(profile.resolve(".hmcl/config")).resolve("instance-game-settings.json");
        if (Files.notExists(config)) Files.writeString(config, "{\"overrideProperties\":[\"runningDirectory\"]}");
        fabric.addProperty("id", PROFILE);
        Files.writeString(profile.resolve(PROFILE + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(fabric));
        if (launcher != null) registerOfficial(launcher, profile);
        return profile;
    }

    private static void registerOfficial(Path launcher, Path profile) throws IOException {
        try {
            String original = Files.readString(launcher);
            JsonObject data = JsonParser.parseString(original).getAsJsonObject();
            var profiles = data.getAsJsonObject("profiles");
            if (profiles == null) {
                profiles = new JsonObject();
                data.add("profiles", profiles);
            }
            var entry = new JsonObject();
            entry.addProperty("name", PROFILE);
            entry.addProperty("type", "custom");
            entry.addProperty("lastVersionId", PROFILE);
            entry.addProperty("gameDir", profile.toAbsolutePath().toString());
            profiles.add("dabois-fabric", entry);
            Path backup = launcher.resolveSibling(launcher.getFileName() + ".before-dabois");
            if (Files.notExists(backup)) Files.copy(launcher, backup, StandardCopyOption.COPY_ATTRIBUTES);
            Path staged = Files.createTempFile(launcher.getParent(), ".dabois-launcher-", ".tmp");
            try {
                Files.writeString(staged, new GsonBuilder().setPrettyPrinting().create().toJson(data));
                if (!Files.readString(launcher).equals(original)) {
                    throw new IOException("Close Minecraft Launcher, then run setup again.");
                }
                Files.move(staged, launcher, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } finally {
                Files.deleteIfExists(staged);
            }
        } catch (RuntimeException e) {
            throw new IOException("Minecraft Launcher’s profile file could not be read.", e);
        }
    }

    private static JsonElement json(String url) throws Exception { return JsonParser.parseString(text(url)); }
    private static String value(JsonObject obj, String name) { return obj.get(name).getAsString(); }

    private static HttpResponse<InputStream> request(String url) throws Exception {
        URI target = URI.create(url);
        if (!"https".equals(target.getScheme())) throw new IOException("The download source must use HTTPS.");
        var response = HTTP.send(HttpRequest.newBuilder(target).timeout(java.time.Duration.ofSeconds(90)).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("The official download server could not serve a required file. Check your internet connection.");
        }
        return response;
    }

    private static String text(String url) throws Exception {
        try (var stream = request(url).body()) {
            return new String(stream.readNBytes(4_000_000), StandardCharsets.UTF_8);
        }
    }

    private static String hash(Path file, String expected) throws Exception {
        var digest = MessageDigest.getInstance(expected.length() == 40 ? "SHA-1" : "SHA-256");
        try (var stream = new java.security.DigestInputStream(Files.newInputStream(file), digest)) {
            stream.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void download(String url, String expected, Path target) throws Exception {
        if (!expected.matches("(?i)[a-f0-9]{40}|[a-f0-9]{64}")) throw new IOException("The source did not provide a valid checksum.");
        if (Files.isRegularFile(target) && hash(target, expected).equalsIgnoreCase(expected)) return;
        Path parent = Files.createDirectories(target.getParent());
        Path pending = Files.createTempFile(parent, ".download-", ".tmp");
        try {
            try (var stream = request(url).body()) { Files.copy(stream, pending, StandardCopyOption.REPLACE_EXISTING); }
            if (!hash(pending, expected).equalsIgnoreCase(expected)) throw new IOException("A downloaded file failed verification.");
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(pending); }
    }

    private static void payload(String name, Path target) throws IOException {
        try (InputStream stream = Setup.class.getResourceAsStream("/payload/" + name)) {
            if (stream == null) throw new IOException("The setup bundle is missing " + name + ".");
            Files.copy(stream, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static Path java() {
        return Path.of(System.getProperty("java.home"), "bin", File.separatorChar == '\\' ? "java.exe" : "java");
    }

    private static Process process(Path folder, String... arguments) throws IOException {
        var command = new java.util.ArrayList<String>();
        command.add(java().toString());
        command.addAll(java.util.List.of(arguments));
        return new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
    }

    private static void run(Path path, String... args) throws Exception {
        Process child = process(path, args);
        try (InputStream log = child.getInputStream()) { Files.copy(log, path.resolve("setup.log"), StandardCopyOption.REPLACE_EXISTING); }
        if (child.waitFor() != 0) throw new IOException("Server setup failed. See setup.log in " + path);
    }

    private void console() {
        try {
            Process child = process(installed, "-Xmx2G", "-jar", "fabric-server-launch.jar", "nogui");
            start.setEnabled(false);
            var view = new JFrame("DABOIS Server");
            var output = new JTextArea(18, 64);
            output.setEditable(false);
            var command = new JTextField(35);
            var send = new JButton("Send command");
            var pair = new JButton("Show pairing code");
            var stop = new JButton("Save and stop");
            var controls = new JPanel();
            for (var item : new Component[]{command, send, pair, stop}) controls.add(item);
            var writer = new PrintWriter(child.getOutputStream(), true);
            send.addActionListener(e -> { writer.println(command.getText()); command.setText(""); });
            command.addActionListener(e -> send.doClick());
            pair.addActionListener(e -> writer.println("dablink code"));
            stop.addActionListener(e -> writer.println("stop"));
            view.add(new JScrollPane(output));
            view.add(controls, BorderLayout.SOUTH);
            view.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            view.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override public void windowClosing(java.awt.event.WindowEvent event) {
                    if (!child.isAlive()) view.dispose();
                    else if (JOptionPane.showConfirmDialog(view, "Save the world and stop this server?",
                            "Stop server", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) writer.println("stop");
                }
            });
            view.pack();
            view.setLocationRelativeTo(frame);
            view.setVisible(true);
            Thread.startVirtualThread(() -> {
                try (var reader = child.inputReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        String next = line;
                        SwingUtilities.invokeLater(() -> output.append(next + "\n"));
                    }
                } catch (IOException ignored) {
                    // The server closed the console stream.
                }
                SwingUtilities.invokeLater(() -> {
                    output.append("\nServer stopped.\n");
                    start.setEnabled(true);
                });
            });
        } catch (IOException e) {
            message("Could not start the server. " + e.getMessage());
        }
    }
}
