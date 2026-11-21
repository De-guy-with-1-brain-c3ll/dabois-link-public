package link.dabois;

import java.io.*;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Properties;
import java.util.jar.*;

public final class HostSetup {
    static final Path DIRECTORY = Path.of("config", "dabois-link");
    static final String INVITE = "dabois-link-invite.properties";

    private HostSetup() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4 || args[0].isBlank()) {
            System.err.println("Usage: java -cp dabois-link.jar link.dabois.HostSetup PUBLIC_HOST [PUBLIC_PORT] [NAME] [LOCAL_PORT]");
            System.exit(2);
        }
        int publicPort = args.length > 1 ? port(args[1]) : 25576;
        String name = args.length > 2 ? args[2] : "DABOIS";
        int listenPort = args.length > 3 ? port(args[3]) : publicPort;
        if (name.isBlank() || name.length() > 64) {
            throw new IllegalArgumentException("Server name must be between 1 and 64 characters.");
        }
        Link.secureDirectory(DIRECTORY);
        Path serverFile = DIRECTORY.resolve("server.properties");
        Path certificateFile = DIRECTORY.resolve("server.p12");
        Properties server = Files.exists(serverFile) ? Link.read(serverFile) : new Properties();
        String password = server.getProperty("password", Link.secret());
        server.setProperty("listenPort", Integer.toString(listenPort));
        server.setProperty("password", password);
        Link.save(serverFile, server);
        if (Files.notExists(certificateFile)) {
            Path temp = Files.createTempFile(DIRECTORY, "key-", ".password");
            Path pending = DIRECTORY.resolve("server.pending.p12");
            try {
                Files.writeString(temp, password);
                String keytool = Path.of(System.getProperty("java.home"), "bin",
                        File.separatorChar == '\\' ? "keytool.exe" : "keytool").toString();
                int status = new ProcessBuilder(keytool, "-genkeypair", "-alias", "server",
                        "-keyalg", "EC", "-groupname", "secp256r1", "-validity", "3650", "-dname", "CN=DABOIS Link",
                        "-storetype", "PKCS12", "-keystore", pending.toString(),
                        "-storepass:file", temp.toString()).inheritIO().start().waitFor();
                if (status != 0) {
                    throw new IOException("keytool failed with exit code " + status);
                }
                Files.move(pending, certificateFile, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(temp);
                Files.deleteIfExists(pending);
            }
        }
        Certificate certificate = keyStore(certificateFile, password).getCertificate("server");
        Properties invite = new Properties();
        invite.setProperty("host", args[0]);
        invite.setProperty("port", Integer.toString(publicPort));
        invite.setProperty("name", name);
        invite.setProperty("fingerprint", Link.HEX.formatHex(Link.digest(certificate.getEncoded())));
        if (server.getProperty("voicePort") != null) {
            invite.setProperty("voicePort", Integer.toString(port(server.getProperty("voicePort"))));
        }
        EmbeddedZeroTier.Settings overlay = EmbeddedZeroTier.Settings.read(server);
        if (overlay != null) {
            invite.setProperty("ztNetwork", Long.toUnsignedString(overlay.network(), 16));
            invite.setProperty("ztHost", overlay.host());
            invite.setProperty("ztPort", Integer.toString(listenPort));
        }
        Path source = Path.of(HostSetup.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path output = Path.of("dabois-link-friends.jar");
        Path temporary = Files.createTempFile(Path.of("."), "dabois-link-friends-", ".tmp");
        try {
            try (JarFile input = new JarFile(source.toFile());
                 JarOutputStream jar = new JarOutputStream(
                         Files.newOutputStream(temporary), new Manifest(input.getManifest()))) {
                var entries = input.entries();
                while (entries.hasMoreElements()) {
                    JarEntry entry = entries.nextElement();
                    if (entry.getName().equals(JarFile.MANIFEST_NAME) || entry.getName().equals(INVITE)) {
                        continue;
                    }
                    jar.putNextEntry(new JarEntry(entry.getName()));
                    try (InputStream data = input.getInputStream(entry)) {
                        data.transferTo(jar);
                    }
                    jar.closeEntry();
                }
                jar.putNextEntry(new JarEntry(INVITE));
                invite.store(jar, "Server invitation");
                jar.closeEntry();
            }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        System.out.println("Share " + output.toAbsolutePath() + " with players.");
    }

    static int port(String value) {
        int port = Integer.parseInt(value);
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("Port must be between 1 and 65535.");
        }
        return port;
    }

    static KeyStore keyStore(Path path, String password) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(path)) {
            keyStore.load(in, password.toCharArray());
        }
        return keyStore;
    }
}
