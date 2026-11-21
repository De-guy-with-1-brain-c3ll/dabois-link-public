package link.dabois;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Runs as the Minecraft service user while the game is stopped. */
public final class AdminApply {
    private static final Path PROPERTIES = Path.of("server.properties");
    private static final Path CONFIG = HostSetup.DIRECTORY;
    private static final Path STAGED = CONFIG.resolve("staged.properties");
    private static final Path REQUEST = CONFIG.resolve("apply-request.properties");
    private static final Path BACKUP = CONFIG.resolve("previous-settings.properties");
    private static final Path MODS = CONFIG.resolve("previous-mods.properties");
    private static final Path STATUS = CONFIG.resolve("apply-status.properties");

    private AdminApply() {}

    public static void main(String[] args) throws IOException {
        switch (args.length == 1 ? args[0] : "") {
            case "apply" -> apply();
            case "rollback" -> rollback();
            case "commit" -> {
                Files.deleteIfExists(STAGED);
                finish("Staged changes applied.");
            }
            default -> throw new IllegalArgumentException("Expected apply, rollback, or commit.");
        }
    }

    private static void apply() throws IOException {
        if (Files.notExists(REQUEST)) {
            throw new IOException("No restart was requested.");
        }
        Properties staged = Link.read(STAGED);
        Properties properties = Link.read(PROPERTIES);
        Properties mods = new Properties();
        for (String key : staged.stringPropertyNames()) {
            if (key.startsWith("setting.")) {
                String property = key.substring(8);
                if (properties.getProperty(property) == null) {
                    throw new IOException("Unknown server setting " + property);
                }
            } else if (key.startsWith("mod.")) {
                String jar = key.substring(4);
                validateJar(jar);
                if (!staged.getProperty(key).matches("true|false")) {
                    throw new IOException("Invalid mod state for " + jar);
                }
                mods.setProperty(jar, Boolean.toString(modState(jar)));
            } else {
                throw new IOException("Unknown staged change " + key);
            }
        }
        Link.save(BACKUP, properties);
        Link.save(MODS, mods);
        for (String key : staged.stringPropertyNames()) {
            if (key.startsWith("setting.")) {
                properties.setProperty(key.substring(8), staged.getProperty(key));
            } else {
                changeMod(key.substring(4), Boolean.parseBoolean(staged.getProperty(key)));
            }
        }
        Link.save(PROPERTIES, properties);
    }

    private static void rollback() throws IOException {
        if (Files.exists(BACKUP)) {
            Link.save(PROPERTIES, Link.read(BACKUP));
        }
        if (Files.exists(MODS)) {
            Properties previous = Link.read(MODS);
            for (String jar : previous.stringPropertyNames()) {
                changeMod(jar, Boolean.parseBoolean(previous.getProperty(jar)));
            }
        }
        finish("Changes could not be applied. The previous configuration was restored.");
    }

    private static void finish(String message) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("message", message);
        Link.save(STATUS, properties);
        Files.deleteIfExists(BACKUP);
        Files.deleteIfExists(MODS);
        Files.deleteIfExists(REQUEST);
    }

    private static void changeMod(String jar, boolean enabled) throws IOException {
        validateJar(jar);
        boolean current = modState(jar);
        if (current != enabled) {
            Path destination = modPath(jar, enabled);
            Files.createDirectories(destination.getParent());
            Files.move(modPath(jar, current), destination, StandardCopyOption.ATOMIC_MOVE);
        }
    }

    private static boolean modState(String jar) throws IOException {
        boolean active = Files.isRegularFile(modPath(jar, true), LinkOption.NOFOLLOW_LINKS);
        boolean inactive = Files.isRegularFile(modPath(jar, false), LinkOption.NOFOLLOW_LINKS);
        if (active == inactive) {
            throw new IOException("Could not locate one installed copy of " + jar);
        }
        return active;
    }

    private static Path modPath(String jar, boolean enabled) {
        return Path.of(enabled ? "mods" : "mods-disabled", jar);
    }

    private static void validateJar(String jar) throws IOException {
        if (!jar.matches("[A-Za-z0-9][A-Za-z0-9_.+ -]{0,149}\\.jar") || jar.equalsIgnoreCase("dabois-link.jar")) {
            throw new IOException("Unsupported mod name.");
        }
    }
}
