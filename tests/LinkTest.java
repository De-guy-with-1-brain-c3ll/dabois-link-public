package link.dabois;

import java.io.*;
import java.net.*;
import java.nio.file.*;

public final class LinkTest {
    private static final byte[] STATUS = { 7, 0, 1, 1, 'x', 0, 1, 1 };
    private static final byte[] LOGIN = { 7, 0, 1, 1, 'x', 0, 1, 2 };

    public static void main(String[] args) throws Exception {
        if (args.length > 0) {
            realServer(args);
            return;
        }
        Guest.Invite invite = Guest.Invite.packaged();
        try (ServerSocket upstream = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
             Host host = new Host(HostSetup.DIRECTORY, "127.0.0.1", upstream.getLocalPort(),
                     name -> name.equalsIgnoreCase("Operator"))) {
            String code = host.invitations.rotate();
            String token = Link.secret();
            String other = Link.secret();
            String admin = Link.secret();
            rejects(() -> Guest.request(invite, "DifferentPlayer", "bad", other));
            Guest.request(invite, "TestPlayer", code, token);
            Guest.request(invite, "DifferentPlayer", code, other);
            rejects(() -> Guest.request(invite, "TestPlayer", code, Link.secret()));
            rejects(() -> Guest.request(invite, "Operator", code, admin));
            rejects(() -> play(invite, admin).close());
            host.invitations.approve("Operator", Invitations.fingerprint(admin).substring(0, 8));
            Guest.request(invite, "Operator", code, admin);
            play(invite, admin).close();
            rejects(() -> Guest.request(new Guest.Invite(invite.host(), invite.port(), invite.name(),
                    "0".repeat(64)), "TestPlayer", code, token));

            guestConnection(invite, token, upstream, STATUS, null);
            guestConnection(invite, token, upstream, LOGIN, "TestPlayer");
            try (Socket socket = play(invite, token)) {
                socket.getOutputStream().write(LOGIN);
                socket.getOutputStream().write(login("OtherPlayer"));
                check(socket.getInputStream().read() == -1, "Wrong username was not rejected");
            }
            check(new Invitations(HostSetup.DIRECTORY.resolve("players.properties")).authenticate(token)
                    .equals("TestPlayer"), "Pairing did not persist");
            host.invitations.rotate();
            rejects(() -> Guest.request(invite, "NewPlayer", code, Link.secret()));
            play(invite, token).close();
            host.revoke("TestPlayer");
            host.revoke("DifferentPlayer");
            host.revoke("Operator");
            rejects(() -> play(invite, token).close());
        }
        System.out.println("PASS: shared code, operator approval, rotation, TLS pin, login identity and revocation.");
    }

    private static void realServer(String[] args) throws Exception {
        Guest.Invite invite = Guest.Invite.packaged();
        String token = Link.secret();
        Guest.request(invite, "TestPlayer", args[0], token);
        String address = Guest.remember(invite, Path.of("test-client.properties"), token);
        int port = Integer.parseInt(address.split(":")[1]);
        try (Socket socket = Link.connect("127.0.0.1", port)) {
            socket.getOutputStream().write(STATUS);
            socket.getOutputStream().write(new byte[]{1, 0});
            byte[] response = socket.getInputStream().readNBytes(30);
            check(response.length == 30, "No Minecraft status response");
        }
        try (Socket socket = Link.connect("127.0.0.1", port)) {
            socket.getOutputStream().write(loginHandshake(Integer.parseInt(args[1])));
            socket.getOutputStream().write(login("TestPlayer"));
            int length = socket.getInputStream().read();
            int packetType = socket.getInputStream().read();
            check(length > 1 && length < 128 && packetType == 2, "Minecraft did not accept offline login");
        }
        System.out.println("PASS: Minecraft server status and initial offline login over the loopback client proxy.");
    }

    private static byte[] loginHandshake(int protocol) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(0);
        while (protocol >= 128) {
            packet.write((protocol & 127) | 128);
            protocol >>>= 7;
        }
        packet.write(protocol);
        packet.write(new byte[]{1, 'x', 0, 1, 2});
        return frame(packet.toByteArray());
    }

    private static byte[] login(String name) throws IOException {
        ByteArrayOutputStream packet = new ByteArrayOutputStream();
        packet.write(0);
        packet.write(name.length());
        packet.write(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        packet.write(new byte[16]);
        return frame(packet.toByteArray());
    }

    private static byte[] frame(byte[] packet) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bytes.write(packet.length);
        bytes.write(packet);
        return bytes.toByteArray();
    }

    private static void guestConnection(Guest.Invite invite, String token, ServerSocket upstream,
                                        byte[] handshake, String loginName) throws Exception {
        try (Socket socket = play(invite, token)) {
            socket.getOutputStream().write(handshake);
            byte[] start = loginName == null ? new byte[0] : login(loginName);
            socket.getOutputStream().write(start);
            upstream.setSoTimeout(3000);
            try (Socket game = upstream.accept()) {
                game.setSoTimeout(3000);
                check(java.util.Arrays.equals(game.getInputStream().readNBytes(handshake.length), handshake),
                        "Handshake did not reach the game");
                check(java.util.Arrays.equals(game.getInputStream().readNBytes(start.length), start),
                        "Login did not reach the game");
                game.getOutputStream().write(42);
                check(socket.getInputStream().read() == 42, "Game response did not reach guest");
            }
        }
    }

    private static Socket play(Guest.Invite invite, String token) throws IOException {
        Socket socket = Guest.tls(invite);
        try {
            DataOutputStream request = new DataOutputStream(socket.getOutputStream());
            request.writeInt(Link.MAGIC);
            request.writeByte(Link.PLAY);
            request.writeUTF(token);
            if (!new DataInputStream(socket.getInputStream()).readBoolean()) {
                throw new IOException("Credential rejected");
            }
            return socket;
        } catch (IOException e) {
            socket.close();
            throw e;
        }
    }

    private static void rejects(Checked op) throws Exception {
        try {
            op.run();
            throw new AssertionError("Expected rejection");
        } catch (IOException expected) {
            // Expected failure.
        }
    }

    private static void check(boolean value, String detail) {
        if (!value) {
            throw new AssertionError(detail);
        }
    }

    private interface Checked {
        void run() throws Exception;
    }
}
