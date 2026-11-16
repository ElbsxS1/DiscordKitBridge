package br.asthen.kitbridge.api;

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
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Everything the plugin still has to tell the bot: final results of requests ({@code kind = "result"}) and later
 * delivery updates such as KIT_PENDING -> KIT_DELIVERED ({@code kind = "event"}).
 *
 * <p>An item is written to disk BEFORE the first send attempt and removed only when the bot confirms it
 * (see {@link #confirm}). It survives restarts, is retried with growing delays, and is never dropped because of a
 * timeout, HTTP 401/403 or 5xx. Items the bot definitively refuses (HTTP 400/422) are kept as dead letters for a
 * person to look at and are only purged after {@link #DEAD_RETENTION_MS}.
 *
 * <p>Ids make everything idempotent: adding the same id twice keeps one item.
 */
public final class OutboxStore {

    public static final long DEAD_RETENTION_MS = 7L * 24 * 3600 * 1000;
    private static final int MAX_ITEMS = 2000;
    private static final long MIN_BACKOFF_MS = 2_000L;
    private static final long MAX_BACKOFF_MS = 5 * 60_000L;

    public static final String RESULT = "result";
    public static final String EVENT = "event";

    public static final class Item {
        public final String id;
        public final String kind;
        public final String requestId;
        public final String payload;
        public final long createdAt;
        public int attempts;
        public long nextAt;
        public boolean dead;
        public String lastError;

        Item(String id, String kind, String requestId, String payload, long createdAt) {
            this.id = id;
            this.kind = kind;
            this.requestId = requestId;
            this.payload = payload;
            this.createdAt = createdAt;
        }

        Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", id);
            m.put("kind", kind);
            m.put("request_id", requestId);
            m.put("payload", payload);
            m.put("created_at", createdAt);
            m.put("attempts", attempts);
            m.put("next_at", nextAt);
            m.put("dead", dead);
            if (lastError != null) m.put("last_error", lastError);
            return m;
        }
    }

    private final Path file; // null = memory only (tests, legacy constructor)
    private final LongSupplier clock;
    private final Consumer<String> warn;
    private final Map<String, Item> items = new LinkedHashMap<>();

    public OutboxStore(Path file, LongSupplier clock, Consumer<String> warn) {
        this.file = file;
        this.clock = clock;
        this.warn = warn;
        if (file != null) load();
    }

    public static OutboxStore inMemory() {
        return new OutboxStore(null, System::currentTimeMillis, m -> { });
    }

    // ------------------------------------------------------------------ queue

    /** Saves the item (idempotent by id). Throws if it could not be persisted, so the caller knows. */
    public synchronized void add(String id, String kind, String requestId, String payload) throws IOException {
        if (items.containsKey(id)) return;
        Item it = new Item(id, kind, requestId, payload, clock.getAsLong());
        items.put(id, it);
        trim();
        try {
            save();
        } catch (IOException e) {
            items.remove(id); // not persisted: do not pretend it is safe
            throw e;
        }
    }

    /** Items that are due now, oldest first. Dead letters are never returned. */
    public synchronized List<Item> due(int max) {
        long now = clock.getAsLong();
        List<Item> out = new ArrayList<>();
        for (Item it : items.values()) {
            if (it.dead || it.nextAt > now) continue;
            out.add(it);
            if (out.size() >= max) break;
        }
        return out;
    }

    /** The bot confirmed it received and processed this item: now (and only now) it is removed. */
    public synchronized void confirm(String id) {
        if (items.remove(id) != null) saveQuietly();
    }

    /** A temporary failure (network, timeout, 5xx, 429, 401/403 ...): keep it and try again later. */
    public synchronized void retryLater(String id, String why) {
        retryAfter(id, why, -1);
    }

    /** Keep it, but wait at least {@code minDelayMs} (e.g. a bot that does not know this endpoint yet). */
    public synchronized void retryAfter(String id, String why, long minDelayMs) {
        Item it = items.get(id);
        if (it == null) return;
        it.attempts++;
        it.lastError = why;
        long backoff = Math.min(MAX_BACKOFF_MS, MIN_BACKOFF_MS << Math.min(it.attempts, 8));
        it.nextAt = clock.getAsLong() + Math.max(backoff, minDelayMs);
        saveQuietly();
    }

    /** The bot definitively refuses this item: keep it as a dead letter, stop retrying. */
    public synchronized void dead(String id, String why) {
        Item it = items.get(id);
        if (it == null) return;
        it.dead = true;
        it.lastError = why;
        saveQuietly();
    }

    public synchronized int size() {
        int n = 0;
        for (Item it : items.values()) if (!it.dead) n++;
        return n;
    }

    public synchronized int deadCount() {
        int n = 0;
        for (Item it : items.values()) if (it.dead) n++;
        return n;
    }

    public synchronized boolean contains(String id) {
        return items.containsKey(id);
    }

    public synchronized Item get(String id) {
        return items.get(id);
    }

    // ------------------------------------------------------------------ persistence

    private void trim() {
        long now = clock.getAsLong();
        items.values().removeIf(it -> it.dead && now - it.createdAt > DEAD_RETENTION_MS);
        if (items.size() <= MAX_ITEMS) return;
        // over the limit: only dead letters may be evicted, live items are never silently thrown away
        List<String> dead = new ArrayList<>();
        for (Item it : items.values()) if (it.dead) dead.add(it.id);
        for (String id : dead) {
            if (items.size() <= MAX_ITEMS) break;
            items.remove(id);
        }
        if (items.size() > MAX_ITEMS) warn.accept("[outbox] " + items.size() + " unsent items: the bot has been unreachable for a long time");
    }

    private void saveQuietly() {
        try {
            save();
        } catch (IOException e) {
            warn.accept("[outbox] could not write the outbox file: " + e.getMessage());
        }
    }

    private void save() throws IOException {
        if (file == null) return;
        List<Object> arr = new ArrayList<>();
        for (Item it : items.values()) arr.add(it.toMap());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("version", 1);
        root.put("items", arr);
        Atomic.write(file, Json.write(root));
    }

    @SuppressWarnings("unchecked")
    private void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            for (Object o : (List<Object>) root.get("items")) {
                Map<String, Object> m = (Map<String, Object>) o;
                Item it = new Item((String) m.get("id"), (String) m.get("kind"), (String) m.get("request_id"),
                        (String) m.get("payload"), ((Number) m.get("created_at")).longValue());
                it.attempts = m.get("attempts") instanceof Number n ? n.intValue() : 0;
                it.nextAt = 0; // after a restart everything is due again
                it.dead = Boolean.TRUE.equals(m.get("dead"));
                it.lastError = m.get("last_error") instanceof String s ? s : null;
                items.put(it.id, it);
            }
        } catch (Exception e) {
            Path aside = file.resolveSibling(file.getFileName() + ".corrupt-" + clock.getAsLong());
            try {
                Files.move(file, aside, StandardCopyOption.REPLACE_EXISTING);
                warn.accept("[outbox] outbox.json was unreadable (" + e.getMessage() + "); kept as " + aside.getFileName()
                        + ". Unsent results in it must be recovered by hand.");
            } catch (IOException moveFail) {
                warn.accept("[outbox] outbox.json is unreadable and could not be moved aside: " + moveFail.getMessage());
            }
        }
    }
}
