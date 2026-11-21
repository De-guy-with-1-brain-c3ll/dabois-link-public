package link.dabois;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import javax.net.ssl.*;

final class Host implements Closeable {
    final Invitations invitations;
    private final SSLServerSocket listener;
    private final String upstream;
    private final int upstreamPort;
    private final int voicePort;
    private final Predicate<String> reserved;
    private final Closeable overlay;
    private AdminGateway admin;
    private final Semaphore capacity = new Semaphore(32);
    private final ConcurrentHashMap<Socket, String> connections = new ConcurrentHashMap<>();
    private final ScheduledExecutorService deadlines = Executors.newSingleThreadScheduledExecutor(task ->
            Thread.ofPlatform().daemon().name("DABOIS Link timeouts").unstarted(task));

    Host(Path directory, String upstream, int upstreamPort) throws Exception {
        this(directory, upstream, upstreamPort, name -> false);
    }

    Host(Path directory, String upstream, int upstreamPort, Predicate<String> reserved) throws Exception {
        this(directory, upstream, upstreamPort, reserved, null);
    }

    Host(Path directory, String upstream, int upstreamPort, Predicate<String> reserved,
         net.minecraft.server.dedicated.DedicatedServer server) throws Exception {
        Properties settings = Link.read(directory.resolve("server.properties"));
        int port = HostSetup.port(settings.getProperty("listenPort"));
        voicePort = settings.containsKey("voicePort") ? HostSetup.port(settings.getProperty("voicePort")) : 0;
        String password = settings.getProperty("password");
        invitations = new Invitations(directory.resolve("players.properties"));
        if (server != null) {
            admin = new AdminGateway(server, directory, invitations);
        }
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(HostSetup.keyStore(directory.resolve("server.p12"), password), password.toCharArray());
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keys.getKeyManagers(), null, null);
        listener = (SSLServerSocket) context.getServerSocketFactory().createServerSocket();
        try {
            listener.setEnabledProtocols(new String[]{"TLSv1.3"});
            String address = settings.getProperty("listenAddress", "");
            listener.bind(address.isBlank() ? new InetSocketAddress(port) : new InetSocketAddress(address, port));
        } catch (IOException | RuntimeException e) {
            listener.close();
            throw e;
        }
        this.upstream = upstream;
        this.upstreamPort = upstreamPort;
        this.reserved = reserved;
        overlay = EmbeddedZeroTier.startServer(directory, settings, listener.getLocalPort(),
                listener.getInetAddress(), listener.getInetAddress().isAnyLocalAddress());
        Thread.startVirtualThread(this::accept);
    }

    int port() {
        return listener.getLocalPort();
    }

    private void accept() {
        while (!listener.isClosed()) {
            try {
                Socket socket = listener.accept();
                if (capacity.tryAcquire()) {
                    connections.put(socket, "");
                    Thread.startVirtualThread(() -> handle(socket));
                } else {
                    Link.close(socket);
                }
            } catch (IOException e) {
                if (!listener.isClosed()) {
                    System.err.println("DABOIS Link stopped accepting connections: " + e.getMessage());
                    close();
                }
            }
        }
    }

    private void handle(Socket incoming) {
        ScheduledFuture<?> deadline = null;
        try (incoming) {
            deadline = deadlines.schedule(() -> Link.close(incoming), Link.TIMEOUT, TimeUnit.MILLISECONDS);
            incoming.setTcpNoDelay(true);
            incoming.setSoTimeout(Link.TIMEOUT);
            DataInputStream in = new DataInputStream(incoming.getInputStream());
            DataOutputStream out = new DataOutputStream(incoming.getOutputStream());
            if (in.readInt() != Link.MAGIC) {
                return;
            }
            switch (in.readUnsignedByte()) {
                case Link.PAIR -> pair(in, out);
                case Link.PLAY -> play(incoming, in, out, deadline);
                case Link.ADMIN -> {
                    if (admin != null) {
                        admin.respond(in, out);
                    }
                }
                case Link.VOICE -> voice(incoming, in, out, deadline);
                default -> { }
            }
        } catch (IOException | RejectedExecutionException ignored) {
            // Failed authentication, a timeout, or a closed Minecraft connection.
        } finally {
            if (deadline != null) {
                deadline.cancel(false);
            }
            connections.remove(incoming);
            capacity.release();
        }
    }

    private void pair(DataInputStream in, DataOutputStream out) throws IOException {
        String name = in.readUTF();
        String code = in.readUTF();
        String token = in.readUTF();
        try {
            invitations.pair(name, code, token, reserved.test(name));
        } catch (IOException e) {
            out.writeBoolean(false);
            out.writeUTF(e.getMessage());
            return;
        }
        out.writeBoolean(true);
    }

    private void play(Socket incoming, DataInputStream in, DataOutputStream out,
                      ScheduledFuture<?> deadline) throws IOException {
        String token = in.readUTF();
        String name;
        synchronized (invitations) {
            name = invitations.authenticate(token);
            if (name != null) {
                connections.put(incoming, name);
            }
        }
        out.writeBoolean(name != null);
        if (name == null) {
            return;
        }
        MinecraftHandshake.InitialPackets initial = MinecraftHandshake.read(in, name);
        try (Socket game = Link.connect(upstream, upstreamPort)) {
            initial.writeTo(game.getOutputStream());
            deadline.cancel(false);
            Link.bridge(incoming, game);
        }
    }

    void revoke(String name) throws IOException {
        synchronized (invitations) {
            invitations.revoke(name);
            disconnect(name);
        }
    }

    private void voice(Socket incoming, DataInputStream in, DataOutputStream out,
                       ScheduledFuture<?> deadline) throws IOException {
        String token = in.readUTF();
        synchronized (invitations) {
            String name = invitations.authenticate(token);
            if (voicePort == 0 || name == null) {
                out.writeBoolean(false);
                return;
            }
            connections.put(incoming, name);
        }
        try (DatagramSocket voice = new DatagramSocket()) {
            voice.connect(new InetSocketAddress(upstream, voicePort));
            out.writeBoolean(true);
            deadline.cancel(false);
            VoiceTunnel.relay(incoming, voice);
        }
    }

    private void disconnect(String name) {
        connections.forEach((socket, owner) -> {
            if (name.equalsIgnoreCase(owner)) {
                Link.close(socket);
            }
        });
    }

    @Override
    public void close() {
        if (overlay != null) {
            Link.close(overlay);
        }
        Link.close(listener);
        connections.keySet().forEach(Link::close);
        deadlines.shutdownNow();
    }
}
