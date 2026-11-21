package link.dabois;

import java.io.*;
import java.nio.charset.StandardCharsets;

final class MinecraftHandshake {
    record InitialPackets(byte[] handshake, byte[] login) {
        void writeTo(OutputStream output) throws IOException {
            writeFrame(output, handshake);
            if (login != null) {
                writeFrame(output, login);
            }
        }
    }

    static InitialPackets read(InputStream in, String name) throws IOException {
        byte[] handshake = frame(in);
        DataInputStream hello = new DataInputStream(new ByteArrayInputStream(handshake));
        if (varInt(hello) != 0) {
            throw new IOException("Invalid Minecraft handshake.");
        }
        varInt(hello);
        string(hello, 1020);
        hello.readUnsignedShort();
        int intent = varInt(hello);
        if (hello.available() != 0 || (intent != 1 && intent != 2)) {
            throw new IOException("Unsupported Minecraft connection type.");
        }
        if (intent == 1) {
            return new InitialPackets(handshake, null);
        }
        byte[] login = frame(in);
        DataInputStream loginStart = new DataInputStream(new ByteArrayInputStream(login));
        if (varInt(loginStart) != 0 || !name.equals(string(loginStart, 16)) || loginStart.available() != 16) {
            throw new IOException("This device is paired to a different Minecraft profile.");
        }
        return new InitialPackets(handshake, login);
    }

    private static byte[] frame(InputStream in) throws IOException {
        return bytes(in, varInt(in), 2048);
    }

    private static String string(InputStream in, int max) throws IOException {
        return new String(bytes(in, varInt(in), max), StandardCharsets.UTF_8);
    }

    private static byte[] bytes(InputStream in, int length, int max) throws IOException {
        if (length < 0 || length > max) {
            throw new IOException("Invalid Minecraft packet size.");
        }
        byte[] bytes = in.readNBytes(length);
        if (bytes.length != length) {
            throw new EOFException();
        }
        return bytes;
    }

    private static int varInt(InputStream in) throws IOException {
        int result = 0;
        for (int shift = 0; shift <= 28; shift += 7) {
            int value = in.read();
            if (value < 0) {
                throw new EOFException();
            }
            if (shift == 28 && (value & 0xF0) != 0) {
                throw new IOException("Invalid Minecraft VarInt.");
            }
            result |= (value & 0x7f) << shift;
            if ((value & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("Invalid Minecraft VarInt.");
    }

    private static void writeFrame(OutputStream out, byte[] packet) throws IOException {
        int length = packet.length;
        while (length >= 128) {
            out.write((length & 127) | 128);
            length >>>= 7;
        }
        out.write(length);
        out.write(packet);
    }
}
