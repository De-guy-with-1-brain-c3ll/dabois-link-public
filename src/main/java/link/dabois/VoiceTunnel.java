package link.dabois;

import java.io.*;
import java.net.*;

/** Carries the voice mod's UDP datagrams on their own authenticated TLS connection. */
final class VoiceTunnel {
    private static final int MAX_DATAGRAM = 4096;
    private static final int IDLE_TIMEOUT = 35_000;

    private VoiceTunnel() {}

    static void relay(Socket stream, DatagramSocket voice) throws IOException {
        stream.setSoTimeout(IDLE_TIMEOUT);
        Thread.startVirtualThread(() -> {
            try {
                byte[] data = new byte[MAX_DATAGRAM + 1];
                while (!stream.isClosed()) {
                    DatagramPacket packet = new DatagramPacket(data, data.length);
                    voice.receive(packet);
                    if (packet.getLength() <= MAX_DATAGRAM) {
                        send(stream.getOutputStream(), data, packet.getLength());
                    }
                }
            } catch (IOException ignored) {
                Link.close(stream);
            }
        });
        DataInputStream input = new DataInputStream(stream.getInputStream());
        while (!stream.isClosed()) {
            byte[] data = receive(input);
            voice.send(new DatagramPacket(data, data.length));
        }
    }

    static final class Local implements Closeable {
        private final DatagramSocket listener;
        private final Guest.Invite invite;
        private final String token;
        private volatile Socket stream;

        Local(Guest.Invite invite, String token) throws IOException {
            this.invite = invite;
            this.token = token;
            listener = new DatagramSocket(new InetSocketAddress("127.0.0.1", invite.voicePort()));
            Thread.startVirtualThread(this::listen);
        }

        private void listen() {
            byte[] data = new byte[MAX_DATAGRAM + 1];
            while (!listener.isClosed()) {
                try {
                    var packet = new DatagramPacket(data, data.length);
                    listener.receive(packet);
                    if (packet.getLength() > MAX_DATAGRAM) {
                        continue;
                    }
                    Socket current = stream;
                    if (current == null || current.isClosed()) {
                        current = connect(packet.getSocketAddress());
                    }
                    try {
                        send(current.getOutputStream(), data, packet.getLength());
                    } catch (IOException e) {
                        Link.close(current);
                    }
                } catch (IOException ignored) {
                    // A new datagram can re-establish the channel after a network interruption.
                }
            }
        }

        private Socket connect(SocketAddress voiceClient) throws IOException {
            Socket connected = Guest.tls(invite);
            try {
                var out = new DataOutputStream(connected.getOutputStream());
                var in = new DataInputStream(connected.getInputStream());
                out.writeInt(Link.MAGIC);
                out.writeByte(Link.VOICE);
                out.writeUTF(token);
                if (!in.readBoolean() || listener.isClosed()) {
                    throw new IOException("Voice channel unavailable.");
                }
                connected.setSoTimeout(IDLE_TIMEOUT);
                stream = connected;
                Thread.startVirtualThread(() -> {
                    try (connected) {
                        while (!listener.isClosed()) {
                            byte[] audio = receive(in);
                            listener.send(new DatagramPacket(audio, audio.length, voiceClient));
                        }
                    } catch (IOException ignored) {
                        // The next voice packet can reconnect.
                    }
                });
                return connected;
            } catch (IOException e) {
                Link.close(connected);
                throw e;
            }
        }

        @Override
        public void close() {
            listener.close();
            Socket active = stream;
            if (active != null) {
                Link.close(active);
            }
        }
    }

    private static byte[] receive(DataInputStream input) throws IOException {
        int length = input.readUnsignedShort();
        if (length > MAX_DATAGRAM) {
            throw new IOException("Voice packet exceeds the maximum size.");
        }
        byte[] data = input.readNBytes(length);
        if (data.length != length) {
            throw new EOFException();
        }
        return data;
    }

    private static void send(OutputStream output, byte[] data, int length) throws IOException {
        byte[] frame = new byte[length + 2];
        System.arraycopy(data, 0, frame, 2, length);
        frame[0] = (byte) (length >>> 8);
        frame[1] = (byte) length;
        output.write(frame);
    }
}
