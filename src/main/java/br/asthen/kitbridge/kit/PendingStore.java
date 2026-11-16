package br.asthen.kitbridge.kit;

import br.asthen.kitbridge.util.Atomic;
import br.asthen.kitbridge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Kits waiting for a player (full inventory, or offline when require-online is false).
 * Each entry holds a full copy of the kit as it was when requested, so a later edit or
 * deletion of the kit cannot change what the player was promised.
 * Keys: "uuid:&lt;uuid&gt;" or "name:&lt;platform&gt;:&lt;normalised-name&gt;".
 *
 * <h3>Safety rules (1.3.0)</h3>
 * <ul>
 *   <li>An unreadable file is NEVER treated as "no pending kits". It is restored from the last
 *       good backup ({@code .bak}); if that is also unusable the file is moved aside to
 *       {@code .corrupt-<timestamp>} (kept on disk, never deleted) and the problem is logged and
 *       listed in {@link #issues()}.</li>
 *   <li>Every entry has a persistent {@link State}. {@link State#CLAIMING} is written BEFORE the
 *       items are given, so a crash between the inventory change and the file write leaves a
 *       marker that is never turned into a second delivery automatically.</li>
 *   <li>An entry is only removed after the removal has been written to disk.</li>
 * </ul>
 */
public final class PendingStore {

    /** Life cycle of one pending delivery. Persisted, so it survives a restart. */
    public enum State {
        /** Waiting for the player (offline or inventory full). */
        PENDING,
        /** A claim started. If found after a restart it is ambiguous: an admin must resolve it. */
        CLAIMING,
        /** Given to the player (kept briefly only if the removal could not be written). */
        DELIVERED,
        /** A claim attempt failed safely (nothing was given); will be tried again. */
        RETRY,
        /** Cannot be delivered without a person looking at it (corrupt kit, ambiguous claim, ...). */
        ERROR;

        /** States a claim may pick up automatically. */
        public boolean claimable() {
            return this == PENDING || this == RETRY;
        }
    }

    public record Entry(String requestId, String kitName, long createdAt, KitData kit,
                        State state, int attempts, long updatedAt, String note) {

        public Entry(String requestId, String kitName, long createdAt, KitData kit) {
            this(requestId, kitName, createdAt, kit, State.PENDING, 0, createdAt, null);
        }

        Entry with(State s, String note, long now) {
            int a = (s == State.CLAIMING) ? attempts + 1 : attempts;
            return new Entry(requestId, kitName, createdAt, kit, s, a, now, note);
        }
    }

    /** What startup recovery found. */
    public record Report(int files, int restoredFromBackup, int quarantined, int interrupted) {
    }

    private final Path dir;
    private final Consumer<String> warn;
    private final java.util.function.LongSupplier clock;
    private final List<String> issues = new CopyOnWriteArrayList<>();
    /** key -> number of claimable entries; lets the server thread ask "anything pending?" without touching disk. */
    private final Map<String, Integer> claimableCache = new ConcurrentHashMap<>();
    private volatile boolean cacheReady;

    public PendingStore(Path dir) {
        this(dir, m -> { }, System::currentTimeMillis);
    }

    public PendingStore(Path dir, Consumer<String> warn) {
        this(dir, warn, System::currentTimeMillis);
    }

    public PendingStore(Path dir, Consumer<String> warn, java.util.function.LongSupplier clock) {
        this.dir = dir;
        this.warn = warn;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ startup

    /**
     * Validates every file once at startup, restores or quarantines the unreadable ones, and builds the
     * in-memory index. Interrupted claims are reported, not retried.
     */
    public synchronized Report recover() {
        claimableCache.clear();
        int files = 0, restored = 0, quarantined = 0, interrupted = 0;
        if (Files.isDirectory(dir)) {
            List<Path> all = new ArrayList<>();
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.getFileName().toString().endsWith(".json")).forEach(all::add);
            } catch (IOException e) {
                issue("Could not list the pending folder " + dir + ": " + e.getMessage());
            }
            for (Path p : all) {
                files++;
                String name = p.getFileName().toString();
                String key = keyOfFile(name);
                int issuesBefore = issues.size();
                List<Entry> entries = load(key);
                if (issues.size() > issuesBefore) {
                    if (issues.get(issues.size() - 1).startsWith("RESTORED")) restored++;
                    else quarantined++;
                }
                for (Entry e : entries) if (e.state() == State.CLAIMING) interrupted++;
            }
        }
        cacheReady = true;
        if (interrupted > 0) {
            issue(interrupted + " pending delivery(ies) were interrupted mid-claim (state CLAIMING). "
                    + "They were NOT redelivered automatically: check the player's inventory and use "
                    + "/dk pending resolve <request_id> delivered|retry.");
        }
        return new Report(files, restored, quarantined, interrupted);
    }

    /** Problems that need an administrator's attention. Never cleared silently. */
    public List<String> issues() {
        return List.copyOf(issues);
    }

    // ------------------------------------------------------------------ reads

    /** Everything that is not yet delivered (any state except DELIVERED). */
    public synchronized List<Entry> list(String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : load(key)) if (e.state() != State.DELIVERED) out.add(e);
        return out;
    }

    /** Entries a claim may deliver right now (PENDING or RETRY). */
    public synchronized List<Entry> claimable(String key) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : load(key)) if (e.state().claimable()) out.add(e);
        return out;
    }

    public synchronized int count(String key) {
        return list(key).size();
    }

    /**
     * Cheap, memory-only check for the server thread: is anything claimable for this key?
     * Falls back to a disk read only if {@link #recover()} was never called.
     */
    public boolean hasClaimable(String key) {
        if (!cacheReady) return !claimable(key).isEmpty();
        return claimableCache.getOrDefault(key, 0) > 0;
    }

    /** Every key that currently has at least one unresolved entry (for admin listings). */
    public synchronized List<String> keys() {
        List<String> out = new ArrayList<>();
        if (!Files.isDirectory(dir)) return out;
        try (Stream<Path> s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".json"))
                    .forEach(p -> out.add(keyOfFile(p.getFileName().toString())));
        } catch (IOException e) {
            issue("Could not list the pending folder: " + e.getMessage());
        }
        return out;
    }

    // ------------------------------------------------------------------ writes

    public synchronized void add(String key, Entry e) throws IOException {
        List<Entry> l = new ArrayList<>(load(key));
        for (Entry x : l) if (x.requestId().equals(e.requestId())) return; // already queued
        l.add(e);
        write(key, l);
    }

    /**
     * Moves one entry to a new state and writes it to disk before returning. If the write fails the
     * exception propagates and the caller MUST NOT assume the transition happened.
     */
    public synchronized Entry transition(String key, String requestId, State to, String note) throws IOException {
        List<Entry> l = new ArrayList<>(load(key));
        for (int i = 0; i < l.size(); i++) {
            if (l.get(i).requestId().equals(requestId)) {
                Entry n = l.get(i).with(to, note, clock.getAsLong());
                l.set(i, n);
                write(key, l);
                return n;
            }
        }
        throw new IOException("pending entry not found: " + requestId);
    }

    public synchronized void remove(String key, String requestId) throws IOException {
        List<Entry> l = new ArrayList<>(load(key));
        l.removeIf(e -> e.requestId().equals(requestId));
        if (l.isEmpty()) {
            Path f = file(key);
            Files.deleteIfExists(f);
            Files.deleteIfExists(bak(key));
            claimableCache.remove(key);
        } else {
            write(key, l);
        }
    }

    /** Re-files every entry of {@code from} under {@code to} (used when a name is resolved to a UUID). */
    public synchronized int moveAll(String from, String to) throws IOException {
        if (from.equals(to)) return 0;
        List<Entry> src = new ArrayList<>(load(from));
        if (src.isEmpty()) return 0;
        List<Entry> dst = new ArrayList<>(load(to));
        int moved = 0;
        for (Entry e : src) {
            boolean exists = false;
            for (Entry x : dst) if (x.requestId().equals(e.requestId())) { exists = true; break; }
            if (!exists) { dst.add(e); moved++; }
        }
        write(to, dst);              // the new home is on disk first ...
        Files.deleteIfExists(file(from)); // ... only then is the old file dropped
        Files.deleteIfExists(bak(from));
        claimableCache.remove(from);
        return moved;
    }

    // ------------------------------------------------------------------ file handling

    private List<Entry> load(String key) {
        Path f = file(key);
        if (!Files.exists(f)) {
            // a lone .bak with no main file means the main was lost: use it, do not pretend nothing is pending
            if (Files.exists(bak(key))) {
                try {
                    List<Entry> fromBak = parse(bak(key));
                    Atomic.write(f, Files.readString(bak(key), StandardCharsets.UTF_8));
                    issue("RESTORED " + f.getFileName() + " from its backup (the main file was missing).");
                    reindex(key, fromBak);
                    return fromBak;
                } catch (Exception ignored) {
                    // fall through: nothing usable
                }
            }
            claimableCache.remove(key);
            return List.of();
        }
        try {
            List<Entry> l = parse(f);
            reindex(key, l);
            return l;
        } catch (Exception primary) {
            return recoverCorrupt(key, f, primary);
        }
    }

    private List<Entry> recoverCorrupt(String key, Path f, Exception primary) {
        Path b = bak(key);
        Path aside = f.resolveSibling(f.getFileName() + ".corrupt-" + clock.getAsLong());
        try {
            Files.move(f, aside, StandardCopyOption.REPLACE_EXISTING); // keep the bad file for a human
        } catch (IOException moveFail) {
            issue("QUARANTINE FAILED for " + f.getFileName() + ": " + moveFail.getMessage()
                    + " (original error: " + primary.getMessage() + "). Nothing was overwritten.");
            claimableCache.remove(key);
            return List.of();
        }
        if (Files.exists(b)) {
            try {
                List<Entry> l = parse(b);
                Atomic.write(f, Files.readString(b, StandardCharsets.UTF_8));
                issue("RESTORED " + f.getFileName() + " from its last good backup. The unreadable file was kept as "
                        + aside.getFileName() + " (" + primary.getMessage() + ").");
                reindex(key, l);
                return l;
            } catch (Exception backupBad) {
                issue("CORRUPT " + f.getFileName() + " and its backup is also unreadable. The file was kept as "
                        + aside.getFileName() + ". Pending kits for " + key + " must be recovered by hand.");
                claimableCache.remove(key);
                return List.of();
            }
        }
        issue("CORRUPT " + f.getFileName() + " and no backup exists. The file was kept as "
                + aside.getFileName() + ". Pending kits for " + key + " must be recovered by hand.");
        claimableCache.remove(key);
        return List.of();
    }

    private void issue(String msg) {
        issues.add(msg);
        warn.accept("[pending] " + msg);
    }

    @SuppressWarnings("unchecked")
    private List<Entry> parse(Path f) throws Exception {
        Map<String, Object> root = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
        Object arr = root.get("entries");
        if (!(arr instanceof List<?> list)) throw new IOException("missing \"entries\" array");
        List<Entry> out = new ArrayList<>();
        for (Object o : list) {
            Map<String, Object> m = (Map<String, Object>) o;
            KitData kit = KitData.fromMap((Map<String, Object>) m.get("kit"));
            State st = State.PENDING;
            Object rawState = m.get("state");
            if (rawState instanceof String s) {
                try {
                    st = State.valueOf(s);
                } catch (IllegalArgumentException e) {
                    throw new IOException("unknown state \"" + s + "\"");
                }
            }
            long created = ((Number) m.get("created_at")).longValue();
            int attempts = m.get("attempts") instanceof Number n ? n.intValue() : 0;
            long updated = m.get("updated_at") instanceof Number n ? n.longValue() : created;
            String note = m.get("note") instanceof String s ? s : null;
            out.add(new Entry((String) m.get("request_id"), (String) m.get("kit_name"), created, kit, st, attempts, updated, note));
        }
        return out;
    }

    private void write(String key, List<Entry> l) throws IOException {
        List<Object> arr = new ArrayList<>();
        for (Entry e : l) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("request_id", e.requestId());
            m.put("kit_name", e.kitName());
            m.put("created_at", e.createdAt());
            m.put("state", e.state().name());
            m.put("attempts", e.attempts());
            m.put("updated_at", e.updatedAt());
            if (e.note() != null) m.put("note", e.note());
            m.put("kit", e.kit().toMap());
            arr.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 2);
        root.put("entries", arr);
        Path f = file(key);
        // keep the last good version before replacing it (we only get here after a successful load)
        if (Files.exists(f)) {
            try {
                parse(f);
                Files.copy(f, bak(key), StandardCopyOption.REPLACE_EXISTING);
            } catch (Exception ignored) {
                // current file unreadable: load() would already have quarantined it; never overwrite a good .bak with junk
            }
        }
        Atomic.write(f, Json.write(root));
        reindex(key, l);
    }

    private void reindex(String key, List<Entry> l) {
        int n = 0;
        for (Entry e : l) if (e.state().claimable()) n++;
        if (n == 0) claimableCache.remove(key);
        else claimableCache.put(key, n);
    }

    private Path file(String key) {
        return dir.resolve(fileName(key) + ".json");
    }

    private Path bak(String key) {
        return dir.resolve(fileName(key) + ".json.bak");
    }

    private static String fileName(String key) {
        return key.replaceAll("[^A-Za-z0-9_.-]", "_");
    }

    /**
     * The file name is a lossy form of the key, so the key is read back from a sidecar-free rule:
     * keys we generate are "uuid:..." or "name:..." and ':' becomes '_'. Only the first '_' is a separator.
     */
    private static String keyOfFile(String fileName) {
        String base = fileName.substring(0, fileName.length() - ".json".length());
        int i = base.indexOf('_');
        if (i < 0) return base;
        return base.substring(0, i) + ":" + base.substring(i + 1).replaceFirst("^([a-z]+)_", "$1:");
    }
}
