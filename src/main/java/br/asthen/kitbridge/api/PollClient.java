package br.asthen.kitbridge.api;

import br.asthen.kitbridge.core.KitRequest;
import br.asthen.kitbridge.core.RequestProcessor;
import br.asthen.kitbridge.core.Status;
import br.asthen.kitbridge.logs.DeliveryLogger;
import br.asthen.kitbridge.util.Json;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbound-only transport (poll mode), for hosts that cannot open an inbound port.
 * <ul>
 *   <li>GET  {base}/plugin/v1/pull?limit=N[&amp;wait_ms=W] -> {"requests":[{request_id, player, platform, kit}]} or 204</li>
 *   <li>POST {base}/plugin/v1/result  -> the final result of a request; confirmed by HTTP 200 {"success":true}</li>
 *   <li>POST {base}/plugin/v1/events  -> later updates (KIT_PENDING -> KIT_DELIVERED); see docs/BOT-INTEGRATION-1.3.0.md</li>
 *   <li>POST {base}/plugin/v1/kits    -> {"kits":[{name, enabled, items, price, price_cents, for_sale}]}</li>
 * </ul>
 *
 * <h3>Long polling</h3>
 * With {@code wait_ms} the bot holds the GET open until a request arrives or the wait ends (204). The client keeps
 * exactly one pull in flight, asks again right after any answer, and backs off progressively when the bot is
 * unreachable. The HTTP timeout is always larger than the wait, so a normal "nothing yet" is never a timeout.
 * Without long polling ({@link Options#longPoll()} false) the classic interval-based {@link #pollOnce()} is used.
 *
 * <h3>Results</h3>
 * Every result goes through the persistent {@link OutboxStore}: saved first, sent after, removed only when the
 * bot confirms. {@code isConfirmed} requires HTTP 2xx AND {@code "success": true} in the body.
 *
 * Everything here blocks, so it runs on its own threads and never on the server thread.
 */
public final class PollClient {

    private static final long KITS_SYNC_MS = 300_000L;
    private static final long AUTH_LOG_EVERY_MS = 60_000L;
    private static final long UNSUPPORTED_RETRY_MS = 10 * 60_000L;

    /** Tunables (see config.yml bridge.poll.*). */
    public record Options(int maxPerPoll, int connectTimeoutSeconds, int httpTimeoutSeconds,
                          boolean longPoll, int waitSeconds, int intervalSeconds) {
        public static Options classic(int maxPerPoll, int timeoutSeconds) {
            return new Options(maxPerPoll, timeoutSeconds, timeoutSeconds, false, 0, 3);
        }
    }

    private final URI base;
    private final String token;
    private final RequestProcessor processor;
    private final DeliveryLogger log;
    private final OutboxStore outbox;
    private final Options opts;
    private final int maxPerPoll;
    private final Duration httpTimeout;
    private final HttpClient http;

    private final AtomicBoolean flushing = new AtomicBoolean();
    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean kitsDirty = new AtomicBoolean(true);
    private final AtomicInteger failures = new AtomicInteger();
    /** Results whose outbox write failed: kept in memory and re-added until the disk accepts them. */
    private final Map<String, String[]> unpersisted = new ConcurrentHashMap<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "DiscordKitBridge-Outbox");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean running;
    private volatile Thread loopThread;
    private volatile CompletableFuture<HttpResponse<String>> inFlight;
    private volatile long nextAllowedAt;
    private volatile long lastKitsSync;
    private volatile long lastAuthLog;
    private volatile boolean eventsUnsupportedLogged;

    public PollClient(String baseUrl, String token, RequestProcessor processor, DeliveryLogger log,
                      OutboxStore outbox, Options options) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(b);
        this.token = token;
        this.processor = processor;
        this.log = log;
        this.outbox = outbox;
        this.opts = options;
        this.maxPerPoll = Math.max(1, options.maxPerPoll());
        int wait = options.longPoll() ? Math.max(1, Math.min(25, options.waitSeconds())) : 0;
        // the HTTP timeout must outlast the wait, otherwise every empty long poll would look like a failure
        int timeoutS = Math.max(Math.max(2, options.httpTimeoutSeconds()), wait > 0 ? wait + 10 : 0);
        this.httpTimeout = Duration.ofSeconds(timeoutS);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.max(2, options.connectTimeoutSeconds())))
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** Classic constructor (kept for compatibility): interval polling, results held in memory only. */
    public PollClient(String baseUrl, String token, RequestProcessor processor, DeliveryLogger log,
                      int maxPerPoll, int timeoutSeconds) {
        this(baseUrl, token, processor, log, OutboxStore.inMemory(), Options.classic(maxPerPoll, timeoutSeconds));
    }

    public boolean isLongPoll() {
        return opts.longPoll();
    }

    /** Effective wait sent as wait_ms (0 when long polling is off). */
    public int waitSeconds() {
        return opts.longPoll() ? Math.max(1, Math.min(25, opts.waitSeconds())) : 0;
    }

    public Duration effectiveHttpTimeout() {
        return httpTimeout;
    }

    public void markKitsDirty() {
        kitsDirty.set(true);
    }

    public int outboxSize() {
        return outbox.size() + unpersisted.size();
    }

    // ------------------------------------------------------------------ lifecycle (long polling)

    /** Starts the single long-poll loop on its own thread. No-op when long polling is off or already started. */
    public synchronized void start() {
        if (!opts.longPoll() || running) return;
        running = true;
        Thread t = new Thread(this::loop, "DiscordKitBridge-LongPoll");
        t.setDaemon(true);
        loopThread = t;
        t.start();
    }

    /** Stops the loop, cancels the pull that is waiting, and releases the threads. Pending results stay on disk. */
    public synchronized void stop() {
        running = false;
        CompletableFuture<HttpResponse<String>> f = inFlight;
        if (f != null) f.cancel(true);
        Thread t = loopThread;
        if (t != null) t.interrupt();
        loopThread = null;
        io.shutdown();
    }

    private void loop() {
        while (running) {
            long started = System.currentTimeMillis();
            boolean empty = false;
            try {
                long waitFor = nextAllowedAt - System.currentTimeMillis();
                if (waitFor > 0) {
                    Thread.sleep(Math.min(waitFor, 5_000L));
                    continue;
                }
                flushOutbox();
                syncKitsIfNeeded();
                empty = !pull(waitSeconds() * 1000L);
                failures.set(0);
            } catch (InterruptedException | CancellationException e) {
                if (!running) return;
            } catch (IOException e) {
                onFailure(e.getMessage());
            } catch (RuntimeException e) {
                onFailure(e.toString());
            }
            // a bot that ignores wait_ms answers 204 at once: do not spin
            if (running && empty && System.currentTimeMillis() - started < 1_000L) {
                try {
                    Thread.sleep(Math.max(1, opts.intervalSeconds()) * 1000L);
                } catch (InterruptedException e) {
                    if (!running) return;
                }
            }
        }
    }

    // ------------------------------------------------------------------ classic polling

    /** One classic poll cycle (interval mode). Never throws. */
    public void pollOnce() {
        if (System.currentTimeMillis() < nextAllowedAt) return; // backing off
        if (!busy.compareAndSet(false, true)) return;
        try {
            flushOutbox();
            syncKitsIfNeeded();
            pull(0);
            failures.set(0);
        } catch (IOException e) {
            onFailure(e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            onFailure(e.toString());
        } finally {
            busy.set(false);
        }
    }

    private void onFailure(String why) {
        int n = failures.incrementAndGet();
        // progressive backoff with a little jitter: 2s, 4s, 8s ... up to 60s
        long waitMs = Math.min(60_000L, 1000L << Math.min(n, 6));
        waitMs += (long) (Math.random() * 500);
        nextAllowedAt = System.currentTimeMillis() + waitMs;
        if (n == 1 || n % 10 == 0) {
            log.log("BOT_UNREACHABLE", "failures", n, "retry_in_ms", waitMs, "reason", why);
        }
    }

    // ------------------------------------------------------------------ pull

    /** @return true when the bot handed over at least one request */
    private boolean pull(long waitMs) throws IOException, InterruptedException {
        String q = "?limit=" + maxPerPoll + (waitMs > 0 ? "&wait_ms=" + waitMs : "");
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/plugin/v1/pull" + q))
                .timeout(httpTimeout).header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        HttpResponse<String> res;
        CompletableFuture<HttpResponse<String>> f = http.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        inFlight = f; // so stop() can cancel the wait
        try {
            res = f.get();
        } catch (ExecutionException e) {
            Throwable c = e.getCause();
            throw new IOException(c != null ? String.valueOf(c.getMessage() != null ? c.getMessage() : c) : "request failed");
        } finally {
            inFlight = null;
        }
        int code = res.statusCode();
        if (code == 204) return false;
        if (code == 401 || code == 403) {
            authError("poll", code);
            throw new IOException("bot rejected the token (HTTP " + code + ")");
        }
        if (code != 200) throw new IOException("unexpected HTTP " + code);

        Object requests;
        try {
            requests = Json.parseObject(res.body()).get("requests");
        } catch (Json.JsonException e) {
            throw new IOException("bot sent invalid JSON: " + e.getMessage());
        }
        if (!(requests instanceof List<?> list) || list.isEmpty()) return false;

        int taken = 0;
        for (Object o : list) {
            if (taken++ >= maxPerPoll) break; // never trust the bot to honour the limit
            if (!(o instanceof Map<?, ?> raw)) continue;
            @SuppressWarnings("unchecked")
            Map<String, Object> m = (Map<String, Object>) raw;
            try {
                KitRequest kr = KitRequest.parse(m);
                processor.submit(kr).whenComplete((rec, err) -> {
                    if (err != null) {
                        postResult(kr.requestId(), ResponseHandler.error(Status.INTERNAL_ERROR, null, kr.requestId()).body());
                    } else {
                        postResult(kr.requestId(), ResponseHandler.result(rec).body());
                    }
                });
            } catch (KitRequest.InvalidRequestException e) {
                log.log("INVALID_REQUEST", "request_id", e.requestId(), "reason", e.getMessage());
                if (e.requestId() != null) {
                    postResult(e.requestId(), ResponseHandler.error(Status.INVALID_REQUEST, e.getMessage(), e.requestId()).body());
                }
            }
        }
        return true;
    }

    private void authError(String where, int code) {
        long now = System.currentTimeMillis();
        if (now - lastAuthLog >= AUTH_LOG_EVERY_MS) {
            lastAuthLog = now;
            log.log("AUTH_ERROR", "where", where, "http", code,
                    "hint", "bridge.secret does not match the bot's DKB_SECRET");
        }
    }

    // ------------------------------------------------------------------ results + events (outbox)

    /**
     * Queues a final result. Safe to call from the server thread: the disk write and the HTTP call run on the
     * outbox thread, and the result is retried until the bot confirms it.
     */
    private void postResult(String requestId, String json) {
        enqueue("result:" + requestId, OutboxStore.RESULT, requestId, json);
    }

    /** Public entry for later delivery updates (KIT_PENDING -> KIT_DELIVERED). */
    public void enqueueEvent(String eventId, String requestId, String json) {
        enqueue("event:" + eventId, OutboxStore.EVENT, requestId, json);
    }

    private void enqueue(String id, String kind, String requestId, String json) {
        try {
            io.execute(() -> {
                try {
                    outbox.add(id, kind, requestId, json);
                } catch (IOException e) {
                    unpersisted.put(id, new String[]{kind, requestId, json});
                    log.log("ERROR", "request_id", requestId, "error", "could not save the result to the outbox: " + e.getMessage());
                }
                flushOutbox();
            });
        } catch (RuntimeException shuttingDown) {
            // the plugin is stopping: keep what we can on disk synchronously
            try {
                outbox.add(id, kind, requestId, json);
            } catch (IOException e) {
                log.log("ERROR", "request_id", requestId, "error", "result lost on shutdown: " + e.getMessage());
            }
        }
    }

    private void drainUnpersisted() {
        for (Map.Entry<String, String[]> e : unpersisted.entrySet()) {
            String[] v = e.getValue();
            try {
                outbox.add(e.getKey(), v[0], v[1], v[2]);
                unpersisted.remove(e.getKey());
            } catch (IOException ignored) {
                // disk still failing; stays in memory
            }
        }
    }

    /** Sends everything that is due. Single-flight: a second caller simply returns. */
    private void flushOutbox() {
        if (!flushing.compareAndSet(false, true)) return;
        try {
            drainUnpersisted();
            for (OutboxStore.Item it : outbox.due(20)) {
                if (Thread.currentThread().isInterrupted()) return;
                if (!send(it)) return; // bot unreachable or rejecting the token: stop this round, try later
            }
        } finally {
            flushing.set(false);
        }
    }

    /** @return false when the rest of the queue should wait (network down, auth error, bot error) */
    private boolean send(OutboxStore.Item it) {
        boolean event = OutboxStore.EVENT.equals(it.kind);
        String path = event ? "/plugin/v1/events" : "/plugin/v1/result";
        try {
            HttpResponse<String> res = http.send(post(path, it.payload), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int c = res.statusCode();
            if (c >= 200 && c < 300) {
                if (isConfirmed(res.body())) {
                    outbox.confirm(it.id);
                    return true;
                }
                outbox.retryLater(it.id, "HTTP " + c + " without {\"success\":true}");
                return true;
            }
            if (c == 401 || c == 403) {
                authError(event ? "event" : "result", c);
                outbox.retryLater(it.id, "HTTP " + c);
                return false;
            }
            if (c == 404) {
                if (event) { // an older bot that has no events endpoint yet: keep the event, check again later
                    if (!eventsUnsupportedLogged) {
                        eventsUnsupportedLogged = true;
                        log.log("EVENTS_UNSUPPORTED", "hint", "the bot has no POST /plugin/v1/events yet; updates stay queued (see docs/BOT-INTEGRATION-1.3.0.md)");
                    }
                    outbox.retryAfter(it.id, "HTTP 404 (events endpoint missing)", UNSUPPORTED_RETRY_MS);
                    return true;
                }
                // the bot does not know (or no longer waits for) this request: it will never accept it
                outbox.dead(it.id, "HTTP 404: the bot does not know this request_id");
                log.log("OUTBOX_REJECTED", "request_id", it.requestId, "http", 404, "kept", "dead letter");
                return true;
            }
            if (c == 400 || c == 409 || c == 413 || c == 422) {
                outbox.dead(it.id, "HTTP " + c);
                log.log("OUTBOX_REJECTED", "request_id", it.requestId, "http", c, "kept", "dead letter");
                return true;
            }
            outbox.retryLater(it.id, "HTTP " + c); // 408, 429, 5xx, anything else: temporary
            return false;
        } catch (IOException e) {
            outbox.retryLater(it.id, String.valueOf(e.getMessage()));
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** The contract: the bot answers 200 {"success": true} only after it processed the item. */
    static boolean isConfirmed(String body) {
        try {
            return Boolean.TRUE.equals(Json.parseObject(body).get("success"));
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ kits

    private void syncKitsIfNeeded() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (!kitsDirty.get() && now - lastKitsSync < KITS_SYNC_MS) return;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("kits", ResponseHandler.kitList(processor.kits()));
        HttpResponse<Void> res = http.send(post("/plugin/v1/kits", Json.write(body)), HttpResponse.BodyHandlers.discarding());
        if (res.statusCode() >= 200 && res.statusCode() < 300) {
            kitsDirty.set(false);
            lastKitsSync = now;
        } else if (res.statusCode() == 404) { // older bot without kit sync: do not insist
            kitsDirty.set(false);
            lastKitsSync = now;
        } else {
            if (res.statusCode() == 401 || res.statusCode() == 403) authError("kits", res.statusCode());
            throw new IOException("bot returned HTTP " + res.statusCode() + " for the kit list");
        }
    }

    private HttpRequest post(String path, String json) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(httpTimeout)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
    }
}
