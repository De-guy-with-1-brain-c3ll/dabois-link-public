package link.dabois;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.util.*;
import javax.net.ssl.*;

final class Link {
    static final int MAGIC = 0x44414232;
    static final int PAIR = 1;
    static final int PLAY = 2;
    static final int ADMIN = 3;
    static final int VOICE = 4;
    static final int TIMEOUT = 8_000;
    static final SecureRandom RANDOM = new SecureRandom();
    static final HexFormat HEX = HexFormat.of();

    private Link() {}

    static String secret() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return HEX.formatHex(bytes);
    }

    static byte[] digest(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    static Properties read(Path path) throws IOException {
        Properties result = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            result.load(in);
        }
        return result;
    }

    static void secureDirectory(Path path) throws IOException {
        if (path.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createDirectories(path, PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------")));
        } else {
            Files.createDirectories(path);
        }
    }

    static void save(Path path, Properties properties) throws IOException {
        Path dir = path.toAbsolutePath().getParent();
        secureDirectory(dir);
        Path temp = Files.createTempFile(dir, ".pending-", ".properties");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                properties.store(out, "DABOIS Link");
            }
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    static Socket connect(String host, int port) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host, port), TIMEOUT);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(TIMEOUT);
            return socket;
        } catch (IOException | RuntimeException e) {
            socket.close();
            throw e;
        }
    }

    static void bridge(Socket first, Socket second) throws IOException {
        first.setSoTimeout(0);
        second.setSoTimeout(0);
        Thread upstream = Thread.startVirtualThread(() -> copy(first, second));
        copy(second, first);
        try {
            upstream.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // A disconnect terminates both directions.
        } finally {
            close(from);
            close(to);
        }
    }

    static void close(Closeable resource) {
        try {
            resource.close();
        } catch (IOException ignored) {
            // The connection is already ending.
        }
    }
}
