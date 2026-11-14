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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Outbound-only transport (poll mode), for hosts that cannot open an inbound port.
 * <ul>
 *   <li>GET  {base}/plugin/v1/pull?limit=N  -> {"requests":[{request_id, player, platform, kit}]}</li>
 *   <li>POST {base}/plugin/v1/result        -> the same JSON the REST API returns for a request</li>
 *   <li>POST {base}/plugin/v1/kits          -> {"kits":[{name, enabled, items}]}</li>
 * </ul>
 * Delivery is at-least-once on the wire and exactly-once in the game: the bot may offer a
 * request again, and the plugin answers repeats from its request history.
 * {@link #pollOnce()} blocks, so call it from an async task, never from the server thread.
 */
public final class PollClient {

    private static final long KITS_SYNC_MS = 300_000L;

    private final URI base;
    private final String token;
    private final RequestProcessor processor;
    private final DeliveryLogger log;
    private final int maxPerPoll;
    private final Duration timeout;
    private final HttpClient http;

    private final AtomicBoolean busy = new AtomicBoolean();
    private final AtomicBoolean kitsDirty = new AtomicBoolean(true);
    private final AtomicInteger failures = new AtomicInteger();
    private final Map<String, String> outbox = new ConcurrentHashMap<>();
    private volatile long nextAllowedAt;
    private volatile long lastKitsSync;

    public PollClient(String baseUrl, String token, RequestProcessor processor, DeliveryLogger log,
                      int maxPerPoll, int timeoutSeconds) {
        String b = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.base = URI.create(b);
        this.token = token;
        this.processor = processor;
        this.log = log;
        this.maxPerPoll = Math.max(1, maxPerPoll);
        this.timeout = Duration.ofSeconds(Math.max(2, timeoutSeconds));
        this.http = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public void markKitsDirty() {
        kitsDirty.set(true);
    }

    public int outboxSize() {
        return outbox.size();
    }

    /** One poll cycle. Never throws. */
    public void pollOnce() {
        if (System.currentTimeMillis() < nextAllowedAt) return; // backing off
        if (!busy.compareAndSet(false, true)) return;
        try {
            flushOutbox();
            syncKitsIfNeeded();
            pull();
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
        long waitMs = Math.min(60_000L, 1000L << Math.min(n, 6));
        nextAllowedAt = System.currentTimeMillis() + waitMs;
        if (n == 1 || n % 10 == 0) {
            log.log("BOT_UNREACHABLE", "failures", n, "retry_in_ms", waitMs, "reason", why);
        }
    }

    // ------------------------------------------------------------------ pull

    private void pull() throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(base + "/plugin/v1/pull?limit=" + maxPerPoll))
                .timeout(timeout).header("Authorization", "Bearer " + token).header("Accept", "application/json").GET().build();
        HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = res.statusCode();
        if (code == 204) return;
        if (code == 401 || code == 403) {
            log.log("AUTH_ERROR", "where", "poll", "http", code);
            throw new IOException("bot rejected the token (HTTP " + code + ")");
        }
        if (code != 200) throw new IOException("unexpected HTTP " + code);

        Object requests;
        try {
            requests = Json.parseObject(res.body()).get("requests");
        } catch (Json.JsonException e) {
            throw new IOException("bot sent invalid JSON: " + e.getMessage());
        }
        if (!(requests instanceof List<?> list)) return;

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
    }

    // ------------------------------------------------------------------ results

    /** Non-blocking: safe to call from the server thread (where futures complete). */
    private void postResult(String requestId, String json) {
        HttpRequest req = post("/plugin/v1/result", json);
        http.sendAsync(req, HttpResponse.BodyHandlers.discarding()).whenComplete((res, err) -> {
            if (err != null || !isDone(res.statusCode())) {
                outbox.put(requestId, json); // retried on the next poll
            } else {
                outbox.remove(requestId);
            }
        });
    }

    /** 2xx = accepted; 4xx other than 408/429 = the bot does not want it (unknown/expired id), stop retrying. */
    private static boolean isDone(int code) {
        return (code >= 200 && code < 300) || (code >= 400 && code < 500 && code != 408 && code != 429);
    }

    private void flushOutbox() throws IOException, InterruptedException {
        int sent = 0;
        for (Map.Entry<String, String> e : outbox.entrySet()) {
            if (sent++ >= 20) break;
            HttpResponse<Void> res = http.send(post("/plugin/v1/result", e.getValue()), HttpResponse.BodyHandlers.discarding());
            if (isDone(res.statusCode())) outbox.remove(e.getKey());
            else throw new IOException("bot returned HTTP " + res.statusCode() + " for a result");
        }
    }

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
            throw new IOException("bot returned HTTP " + res.statusCode() + " for the kit list");
        }
    }

    private HttpRequest post(String path, String json) {
        return HttpRequest.newBuilder(URI.create(base + path)).timeout(timeout)
                .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build();
    }
}
