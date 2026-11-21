package link.dabois;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

final class EmbeddedZeroTier {
    record Settings(long network, String host, int port) {
        static Settings read(Properties settings) {
            String network = settings.getProperty("ztNetwork", "").strip();
            if (network.isEmpty()) {
                return null;
            }
            if (!network.matches("(?i)[0-9a-f]{16}") || network.equals("0000000000000000")) {
                throw new IllegalArgumentException("A 16-digit ZeroTier network ID is required.");
            }
            String host = settings.getProperty("ztHost", "").strip();
            if (!host.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                throw new IllegalArgumentException("The server needs an embedded ZeroTier IPv4 address.");
            }
            for (String part : host.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("Invalid embedded ZeroTier IPv4 address.");
                }
            }
            return new Settings(Long.parseUnsignedLong(network, 16), host,
                    HostSetup.port(settings.getProperty("ztPort", settings.getProperty("listenPort", "25576"))));
        }
    }

    private static final AtomicBoolean STARTING = new AtomicBoolean();
    private static volatile Settings client;
    private static volatile boolean networkAvailable;
    private static volatile String status = "";

    private EmbeddedZeroTier() {}

    static String status() {
        return status;
    }

    static void startClient(Path directory, Settings settings) {
        if (settings == null || !STARTING.compareAndSet(false, true)) {
            return;
        }
        client = settings;
        status = "ZeroTier is starting…";
        worker("DABOIS ZeroTier client", () -> start(directory, settings, null));
    }

    static Closeable startServer(Path directory, Properties properties, int port, InetAddress local, boolean wildcard) {
        try {
            Settings settings = Settings.read(properties);
            if (settings == null || !STARTING.compareAndSet(false, true)) {
                return null;
            }
            var ingress = new Ingress(wildcard ? InetAddress.getLoopbackAddress() : local, port);
            worker("DABOIS ZeroTier server", () -> start(directory, settings, ingress));
            return ingress;
        } catch (RuntimeException e) {
            System.err.println("DABOIS Link ZeroTier configuration: " + e.getMessage());
            return null;
        }
    }

    private static void start(Path directory, Settings settings, Ingress ingress) {
        try {
            Path state = directory.resolve("embedded-zerotier").toAbsolutePath();
            Link.secureDirectory(state);
            load(state);
            if (startNode(state.toString(), settings.network) != 0) {
                throw new IOException("The embedded ZeroTier node could not start.");
            }
            String previous = "";
            while (ingress == null || !ingress.closed) {
                boolean ready = networkReady();
                networkAvailable = ready;
                long id = nodeId();
                String current = ready ? "Embedded ZeroTier ready"
                        : id == 0 ? "ZeroTier is connecting…" : "ZeroTier node " + "%010x".formatted(id) + " is waiting for the network";
                status = current;
                if (ingress != null && !previous.equals(current)) {
                    System.out.println("DABOIS Link: " + current);
                }
                previous = current;
                if (ready && ingress != null) {
                    ingress.listen(settings.port);
                    return;
                }
                Thread.sleep(500);
            }
        } catch (IOException | LinkageError | RuntimeException e) {
            status = "Embedded ZeroTier unavailable. Using the normal connection.";
            System.err.println("DABOIS Link embedded ZeroTier: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void load(Path state) throws IOException {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform = os.startsWith("windows") ? "windows" : os.startsWith("mac") ? "macos"
                : os.equals("linux") ? "linux" : "unknown";
        String arch = switch (System.getProperty("os.arch")) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "arm64";
            default -> "unknown";
        };
        String library = System.mapLibraryName("dabois_zerotier");
        String resource = "/dabois-zt/" + platform + "-" + arch + "/" + library;
        try (InputStream in = EmbeddedZeroTier.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("This build does not include the native library for " + platform + "-" + arch + ".");
            }
            byte[] bytes = in.readNBytes(32 * 1024 * 1024 + 1);
            if (bytes.length == 0 || bytes.length > 32 * 1024 * 1024) {
                throw new IOException("Invalid embedded ZeroTier library size.");
            }
            Path binaries = state.resolve("native-" + Link.HEX.formatHex(Link.digest(bytes)));
            Link.secureDirectory(binaries);
            Path binary = binaries.resolve(library);
            if (Files.notExists(binary)) {
                Path temp = Files.createTempFile(binaries, ".pending-", ".tmp");
                try {
                    Files.write(temp, bytes);
                    Files.move(temp, binary, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(temp);
                }
            }
            if (!Files.isRegularFile(binary, LinkOption.NOFOLLOW_LINKS) || Files.size(binary) != bytes.length
                    || !java.security.MessageDigest.isEqual(Link.digest(Files.readAllBytes(binary)), Link.digest(bytes))) {
                throw new IOException("The embedded ZeroTier library could not be verified.");
            }
            System.load(binary.toString());
        }
    }

    static Socket connect(Settings settings) {
        if (settings == null || !settings.equals(client) || !networkAvailable) {
            return null;
        }
        int fd = open(settings.host, settings.port, 950);
        if (fd < 0) {
            return null;
        }
        Socket application = new Socket();
        Socket relay = null;
        try (ServerSocket pair = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            pair.setSoTimeout(1000);
            application.connect(pair.getLocalSocketAddress(), 1000);
            relay = pair.accept();
            if (!relay.getInetAddress().isLoopbackAddress() || relay.getPort() != application.getLocalPort()) {
                throw new IOException("Could not create the in-process ZeroTier connection.");
            }
            application.setSoTimeout(Link.TIMEOUT);
            application.setTcpNoDelay(true);
            bridge(relay, fd, () -> {});
            return application;
        } catch (IOException | RuntimeException e) {
            if (relay != null) {
                Link.close(relay);
            }
            Link.close(application);
            closeSocket(fd);
            return null;
        }
    }

    private static void bridge(Socket local, int fd, Runnable done) {
        worker("DABOIS ZeroTier download", () -> {
            AtomicBoolean closing = new AtomicBoolean();
            Runnable terminate = () -> {
                if (closing.compareAndSet(false, true)) {
                    Link.close(local);
                    shutdownSocket(fd);
                }
            };
            Thread upload = worker("DABOIS ZeroTier upload", () -> {
                byte[] buffer = new byte[16_384];
                try {
                    InputStream in = local.getInputStream();
                    int count;
                    while ((count = in.read(buffer)) != -1) {
                        int written = 0;
                        while (written < count) {
                            int sent = writeSocket(fd, buffer, written, count - written);
                            if (sent <= 0) {
                                return;
                            }
                            written += sent;
                        }
                    }
                } catch (IOException ignored) {
                    // Either side can close a Minecraft connection.
                } finally {
                    terminate.run();
                }
            });
            try {
                byte[] buffer = new byte[16_384];
                OutputStream out = local.getOutputStream();
                int read;
                while ((read = readSocket(fd, buffer)) > 0) {
                    out.write(buffer, 0, read);
                }
            } catch (IOException ignored) {
                // Either side can close a Minecraft connection.
            } finally {
                terminate.run();
                try {
                    upload.join();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    closeSocket(fd);
                    done.run();
                }
            }
        });
    }

    private static Thread worker(String name, Runnable task) {
        return Thread.ofPlatform().daemon().name(name).start(task);
    }

    private static final class Ingress implements Closeable {
        private final InetAddress local;
        private final int localPort;
        private final Semaphore capacity = new Semaphore(32);
        private volatile boolean closed;

        Ingress(InetAddress local, int localPort) {
            this.local = local;
            this.localPort = localPort;
        }

        void listen(int port) throws IOException {
            int socket = openListener(port);
            if (socket < 0) {
                throw new IOException("Could not listen for embedded ZeroTier connections.");
            }
            try {
                while (!closed) {
                    int fd = acceptSocket(socket);
                    if (fd == -2) {
                        continue;
                    }
                    if (fd < 0) {
                        if (!closed) {
                            throw new IOException("The embedded ZeroTier listener stopped.");
                        }
                        return;
                    }
                    if (!capacity.tryAcquire()) {
                        closeSocket(fd);
                        continue;
                    }
                    try {
                        Socket backend = Link.connect(local.getHostAddress(), localPort);
                        backend.setSoTimeout(0);
                        bridge(backend, fd, capacity::release);
                    } catch (IOException e) {
                        closeSocket(fd);
                        capacity.release();
                    }
                }
            } finally {
                closeSocket(socket);
            }
        }

        @Override
        public void close() {
            closed = true;
        }
    }

    private static native int startNode(String path, long network);
    private static native long nodeId();
    private static native boolean networkReady();
    private static native int open(String host, int port, int timeout);
    private static native int openListener(int port);
    private static native int acceptSocket(int listener);
    private static native int readSocket(int fd, byte[] buffer);
    private static native int writeSocket(int fd, byte[] buffer, int offset, int length);
    private static native void shutdownSocket(int fd);
    private static native void closeSocket(int fd);
}
