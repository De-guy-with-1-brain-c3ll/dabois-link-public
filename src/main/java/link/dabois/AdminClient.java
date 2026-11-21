package link.dabois;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

/** Sends native dashboard actions over the existing paired TLS connection. */
final class AdminClient {
    private AdminClient() {}

    static JsonObject request(Guest.Invite invite, Path pairing, JsonObject payload) throws IOException {
        if (!Files.isRegularFile(pairing)) {
            throw new IOException("Pair this device before opening Server Admin.");
        }
        String token = Link.read(pairing).getProperty("token", "");
        try (var socket = Guest.tls(invite)) {
            var out = new DataOutputStream(socket.getOutputStream());
            var in = new DataInputStream(socket.getInputStream());
            out.writeInt(Link.MAGIC);
            out.writeByte(Link.ADMIN);
            out.writeUTF(token);
            out.writeUTF(payload.toString());
            if (!in.readBoolean()) {
                throw new IOException(in.readUTF());
            }
            return JsonParser.parseString(in.readUTF()).getAsJsonObject();
        }
    }
}
