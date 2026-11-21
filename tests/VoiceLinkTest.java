package link.dabois;

import java.net.*;
import java.nio.file.*;
import java.util.Arrays;

public final class VoiceLinkTest {
    public static void main(String[] args) throws Exception {
        var loopback = InetAddress.getByName("127.0.0.1");
        var directory = HostSetup.DIRECTORY;
        var settings = Link.read(directory.resolve("server.properties"));
        var packaged = Guest.Invite.packaged();
        try (var voiceBackend = new DatagramSocket(0, loopback);
             var localVoiceClient = new DatagramSocket(0, loopback)) {
            int unusedUdpPort;
            try (var available = new DatagramSocket(0, loopback)) {
                unusedUdpPort = available.getLocalPort();
            }
            settings.setProperty("voicePort", Integer.toString(voiceBackend.getLocalPort()));
            Link.save(directory.resolve("server.properties"), settings);
            var invite = new Guest.Invite(packaged.host(), packaged.port(), packaged.name(),
                    packaged.fingerprint(), unusedUdpPort);
            try (var host = new Host(directory, "127.0.0.1", 25565)) {
                String token = Link.secret();
                Guest.request(invite, "VoiceTester", host.invitations.code(), token);
                try (var local = new VoiceTunnel.Local(invite, token)) {
                    voiceBackend.setSoTimeout(6000);
                    localVoiceClient.setSoTimeout(6000);
                    for (int sample : new int[]{2, 11, 1275}) {
                        byte[] audio = new byte[sample];
                        Link.RANDOM.nextBytes(audio);
                        localVoiceClient.send(new DatagramPacket(audio, audio.length, loopback, unusedUdpPort));
                        var packet = new DatagramPacket(new byte[2048], 2048);
                        voiceBackend.receive(packet);
                        verify(audio, packet);
                        voiceBackend.send(packet);
                        var result = new DatagramPacket(new byte[2048], 2048);
                        localVoiceClient.receive(result);
                        verify(audio, result);
                    }
                }
            }
        }
        System.out.println("PASS: independent authenticated voice stream moved real UDP datagrams in both directions.");
    }

    private static void verify(byte[] expected, DatagramPacket actual) {
        if (!Arrays.equals(expected, Arrays.copyOf(actual.getData(), actual.getLength()))) {
            throw new AssertionError("Voice datagram changed in transit");
        }
    }
}
