package link.dabois;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;

public final class AdminTest {
    public static void main(String[] args) throws Exception {
        String code = args[0];
        String mainJar = args[1];
        Guest.Invite invite = Guest.Invite.packaged();
        String owner = Link.secret();
        String guest = Link.secret();
        Guest.request(invite, "TestOwner", code, owner);
        Guest.request(invite, "TestGuest", code, guest);
        checkDenied(invite, owner);
        checkDenied(invite, guest);

        String java = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        var bootstrap = new ProcessBuilder(java, "-cp", mainJar, "link.dabois.AdminBootstrap", "TestOwner")
                .inheritIO().start();
        if (bootstrap.waitFor() != 0) {
            throw new AssertionError("Owner bootstrap failed");
        }

        expect(invite, owner, "{\"action\":\"snapshot\"}", "\"admin\":\"TestOwner\"");
        checkDenied(invite, guest);
        expect(invite, owner, "{\"action\":\"role\",\"player\":\"TestGuest\",\"role\":\"superop\"}", "TestGuest");
        expect(invite, guest, "{\"action\":\"snapshot\"}", "\"admin\":\"TestGuest\"");
        expect(invite, owner, "{\"action\":\"role\",\"player\":\"TestGuest\",\"role\":\"remove-superop\"}", "TestOwner");
        checkDenied(invite, guest);

        expect(invite, owner, "{\"action\":\"role\",\"player\":\"OtherPlayer\",\"role\":\"2\"}",
                "\"operators\":[\"OtherPlayer\"]");
        expect(invite, owner, "{\"action\":\"ban\",\"player\":\"OtherPlayer\",\"reason\":\"Test ban\"}",
                "\"bans\":[\"OtherPlayer\"]");
        expect(invite, owner, "{\"action\":\"pardon\",\"player\":\"OtherPlayer\"}", "\"bans\":[]");
        expect(invite, owner, "{\"action\":\"difficulty\",\"difficulty\":\"hard\"}", "\"difficulty\":\"hard\"");
        expect(invite, owner, "{\"action\":\"world\",\"world\":\"FreshWorld\",\"seed\":\"42\",\"create\":true}",
                "\"level-name\":\"FreshWorld\"");
        expect(invite, owner, "{\"action\":\"settings\",\"settings\":{\"motd\":\"A new message\"}}",
                "\"motd\":\"A new message\"");
        expect(invite, owner, "{\"action\":\"mod\",\"mod\":\"Example.jar\",\"enabled\":true}", "\"Example.jar\":true");
        expect(invite, owner, "{\"action\":\"apply\"}", "\"restarting\":true");
        System.out.println("PASS: paired-device access control, role delegation, bans, difficulty, staging and apply request.");
    }

    private static void checkDenied(Guest.Invite invite, String token) throws IOException {
        try {
            request(invite, token, "{\"action\":\"snapshot\"}");
            throw new AssertionError("A regular device received administrator access");
        } catch (IOException e) {
            if (!e.getMessage().contains("does not have Super Operator access")) {
                throw e;
            }
        }
    }

    private static void expect(Guest.Invite invite, String token, String query, String expected) throws IOException {
        if (!request(invite, token, query).contains(expected)) {
            throw new AssertionError("Dashboard did not apply " + query + " as expected");
        }
    }

    private static String request(Guest.Invite invite, String token, String query) throws IOException {
        try (Socket socket = Guest.tls(invite)) {
            var out = new DataOutputStream(socket.getOutputStream());
            var in = new DataInputStream(socket.getInputStream());
            out.writeInt(Link.MAGIC);
            out.writeByte(Link.ADMIN);
            out.writeUTF(token);
            out.writeUTF(query);
            if (!in.readBoolean()) {
                throw new IOException(in.readUTF());
            }
            return in.readUTF();
        }
    }
}
