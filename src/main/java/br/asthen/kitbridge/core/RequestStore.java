package br.asthen.kitbridge.core;

import br.asthen.kitbridge.util.Atomic;
import br.asthen.kitbridge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Remembers every request id, forever within the retention window, and survives restarts.
 * This is what guarantees a request is never processed twice: the same request_id always
 * gets the original result back.
 */
public final class RequestStore implements AutoCloseable {

    public record Registration(RequestRecord record, boolean duplicate) {
    }

    private final Path file;
    private final int maxEntries;
    private final long retentionMs;
    private final LongSupplier clock;
    private final Consumer<String> warn;
    private final Map<String, RequestRecord> records = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordKitBridge-Store");
        t.setDaemon(true);
        return t;
    });
    private final AtomicBoolean writeScheduled = new AtomicBoolean();

    public RequestStore(Path file, int maxEntries, long retentionMs, LongSupplier clock, Consumer<String> warn) {
        this.file = file;
        this.maxEntries = maxEntries;
        this.retentionMs = retentionMs;
        this.clock = clock;
        this.warn = warn;
    }

    /** Loads the file. Requests that were mid-flight when the server stopped are closed as INTERNAL_ERROR. */
    @SuppressWarnings("unchecked")
    public void load() {
        if (!Files.exists(file)) return;
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(file, StandardCharsets.UTF_8));
            long now = clock.getAsLong();
            for (Object o : (List<Object>) root.get("requests")) {
                RequestRecord r = RequestRecord.fromMap((Map<String, Object>) o);
                if (!r.status().isTerminal()) {
                    r.set(Status.INTERNAL_ERROR, "Interrupted by a server restart; check the player's inventory before retrying", now);
                }
                records.put(r.requestId(), r);
            }
        } catch (Exception e) {
            warn.accept("requests.json could not be read (" + e.getMessage() + "); starting with an empty history");
        }
    }

    /** Atomically registers a new request, or returns the existing one. */
    public Registration register(KitRequest req) {
        RequestRecord fresh = new RequestRecord(req, clock.getAsLong());
        RequestRecord existing = records.putIfAbsent(req.requestId(), fresh);
        if (existing != null) return new Registration(existing, true);
        markDirty();
        return new Registration(fresh, false);
    }

    public Optional<RequestRecord> get(String requestId) {
        return Optional.ofNullable(records.get(requestId));
    }

    public void update(RequestRecord r, Status s, String message) {
        r.set(s, message, clock.getAsLong());
        if (s.isTerminal()) markDirty();
    }

    private void markDirty() {
        if (writeScheduled.compareAndSet(false, true)) {
            try {
                io.execute(() -> {
                    writeScheduled.set(false);
                    writeNow();
                });
            } catch (RuntimeException closed) {
                writeScheduled.set(false);
            }
        }
    }

    private synchronized void writeNow() {
        long cutoff = clock.getAsLong() - retentionMs;
        records.values().removeIf(r -> r.status().isTerminal() && r.updatedAt() < cutoff);
        List<RequestRecord> all = new ArrayList<>(records.values());
        all.sort(Comparator.comparingLong(RequestRecord::createdAt));
        for (int i = 0; all.size() - i > maxEntries; i++) {
            RequestRecord old = all.get(i);
            if (old.status().isTerminal()) records.remove(old.requestId());
        }
        List<Object> arr = new ArrayList<>();
        for (RequestRecord r : records.values()) arr.add(r.toMap());
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("requests", arr);
        try {
            Atomic.write(file, Json.write(root));
        } catch (IOException e) {
            warn.accept("Could not save requests.json: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        io.shutdown();
        try {
            io.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        writeNow();
    }
}
