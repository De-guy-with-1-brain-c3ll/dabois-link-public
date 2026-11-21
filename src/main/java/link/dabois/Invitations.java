package link.dabois;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

final class Invitations {
    private static final long WINDOW = Duration.ofMinutes(1).toNanos();
    private static final long REQUEST_LIFETIME = Duration.ofMinutes(5).toNanos();
    private static final long CODE_LIFETIME = Duration.ofMinutes(15).toMillis();
    private static final int MAX_FAILURES = 5;

    private record Pending(String name, String hash, long expires) {}
    private final Path file;
    private final Path codeFile;
    private final Map<String, Pending> pending = new HashMap<>();
    private final ArrayDeque<Long> failures = new ArrayDeque<>();
    private Properties paired;
    private String code;
    private long expiresAt;

    Invitations(Path file) throws IOException {
        this.file = file;
        codeFile = file.resolveSibling("join-code.properties");
        paired = Files.exists(file) ? Link.read(file) : new Properties();
        if (Files.exists(codeFile)) {
            Properties saved = Link.read(codeFile);
            code = saved.getProperty("code");
            expiresAt = Long.parseLong(saved.getProperty("expiresAt", "0"));
        }
    }

    static boolean validName(String name) {
        return name.matches("[A-Za-z0-9_]{1,16}");
    }

    static boolean validToken(String token) {
        return token.matches("[a-f0-9]{64}");
    }

    synchronized String code() throws IOException {
        return expired() ? rotate() : code;
    }

    synchronized String rotate() throws IOException {
        String next;
        do {
            next = String.format(Locale.ROOT, "%04d", Link.RANDOM.nextInt(10_000));
        } while (next.equals(code));
        Properties saved = new Properties();
        saved.setProperty("code", next);
        long expires = System.currentTimeMillis() + CODE_LIFETIME;
        saved.setProperty("expiresAt", Long.toString(expires));
        Link.save(codeFile, saved);
        code = next;
        expiresAt = expires;
        pending.clear();
        return code;
    }

    synchronized void pair(String name, String entered, String token, boolean reserved) throws IOException {
        if (!validName(name) || !validToken(token)) {
            throw new IOException("Use a valid Minecraft profile name.");
        }
        String owner = authenticate(token);
        if (owner != null) {
            if (name.equals(owner)) {
                return;
            }
            throw new IOException("This device credential is already paired to another profile.");
        }
        long now = System.nanoTime();
        while (!failures.isEmpty() && now - failures.getFirst() >= WINDOW) {
            failures.removeFirst();
        }
        if (failures.size() >= MAX_FAILURES) {
            throw new IOException("Too many failed attempts. Try again in one minute.");
        }
        if (expired() || !code.equals(entered)) {
            failures.addLast(now);
            throw new IOException("Incorrect server code. Ask your host for the current code.");
        }
        String key = name.toLowerCase(Locale.ROOT);
        if (paired.containsKey(key)) {
            throw new IOException("That profile is already paired. Ask the host to reset it.");
        }
        String hash = fingerprint(token);
        if (reserved) {
            pending.values().removeIf(request -> now - request.expires >= 0);
            if (pending.size() >= 32 && !pending.containsKey(hash)) {
                throw new IOException("Too many pending requests. Try again later.");
            }
            pending.put(hash, new Pending(name, hash, now + REQUEST_LIFETIME));
            throw new IOException("Ask the host to approve device " + hash.substring(0, 8) + ", then press Pair again.");
        }
        savePair(name, hash);
    }

    synchronized void approve(String name, String device) throws IOException {
        List<Pending> matches = pending.values().stream()
                .filter(request -> request.name.equals(name) && request.hash.startsWith(device))
                .filter(request -> System.nanoTime() - request.expires < 0).toList();
        if (!device.matches("[a-f0-9]{8}") || matches.size() != 1
                || paired.containsKey(name.toLowerCase(Locale.ROOT))) {
            throw new IOException("No matching fresh approval request for this profile and device.");
        }
        savePair(name, matches.getFirst().hash);
    }

    private void savePair(String name, String hash) throws IOException {
        Properties updated = copy();
        updated.setProperty(name.toLowerCase(Locale.ROOT), name + ":" + hash);
        Link.save(file, updated);
        paired = updated;
        pending.values().removeIf(request -> request.name.equalsIgnoreCase(name));
    }

    synchronized String authenticate(String token) {
        if (!validToken(token)) {
            return null;
        }
        byte[] hash = fingerprint(token).getBytes(StandardCharsets.US_ASCII);
        for (String key : paired.stringPropertyNames()) {
            String[] value = paired.getProperty(key).split(":", 2);
            if (value.length == 2 && MessageDigest.isEqual(value[1].getBytes(StandardCharsets.US_ASCII), hash)) {
                return value[0];
            }
        }
        return null;
    }

    synchronized String deviceHash(String name) {
        String[] entry = paired.getProperty(name.toLowerCase(Locale.ROOT), "").split(":", 2);
        return entry.length == 2 && entry[0].equals(name) ? entry[1] : null;
    }

    synchronized List<String> names() {
        return paired.stringPropertyNames().stream()
                .map(key -> paired.getProperty(key).split(":", 2)[0]).sorted().toList();
    }

    synchronized void revoke(String name) throws IOException {
        Properties updated = copy();
        updated.remove(name.toLowerCase(Locale.ROOT));
        Link.save(file, updated);
        paired = updated;
        pending.values().removeIf(request -> request.name.equalsIgnoreCase(name));
    }

    private Properties copy() {
        Properties updated = new Properties();
        updated.putAll(paired);
        return updated;
    }

    static String fingerprint(String token) {
        return Link.HEX.formatHex(Link.digest(token.getBytes(StandardCharsets.US_ASCII)));
    }

    private boolean expired() {
        return code == null || System.currentTimeMillis() >= expiresAt;
    }
}
