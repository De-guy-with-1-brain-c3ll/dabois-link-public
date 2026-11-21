package link.dabois;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Grants the initial dashboard role from the trusted local server shell. */
public final class AdminBootstrap {
    private AdminBootstrap() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 1 || !Invitations.validName(args[0])) {
            throw new IllegalArgumentException("Usage: AdminBootstrap ExactPairedPlayerName");
        }
        Path config = HostSetup.DIRECTORY;
        Invitations invitations = new Invitations(config.resolve("players.properties"));
        String hash = invitations.deviceHash(args[0]);
        if (hash == null) {
            throw new IOException("That exact player name is not paired. Pair this device first.");
        }
        Path file = config.resolve("superoperators.properties");
        Properties operators = Files.exists(file) ? Link.read(file) : new Properties();
        operators.setProperty(hash, args[0]);
        Link.save(file, operators);
        System.out.println("Super Operator access enabled for " + args[0] + "'s paired device.");
    }
}
