package br.asthen.kitbridge.api;

import br.asthen.kitbridge.api.ResponseHandler.Response;
import br.asthen.kitbridge.core.KitRequest;
import br.asthen.kitbridge.core.RequestProcessor;
import br.asthen.kitbridge.core.RequestRecord;
import br.asthen.kitbridge.core.Status;
import br.asthen.kitbridge.logs.DeliveryLogger;
import br.asthen.kitbridge.security.Authentication;
import br.asthen.kitbridge.security.RateLimiter;
import br.asthen.kitbridge.security.Validators;
import br.asthen.kitbridge.util.Json;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Inbound REST API (server mode). Order of checks on every request:
 * rate limit by IP -> (health is public) -> bearer token -> route -> validate body.
 * Nothing here can run a command: the only action available is "deliver a registered kit".
 */
public final class ApiServer {

    private static final String BASE = "/api/v1";

    private final String host;
    private final int port;
    private final Authentication auth;
    private final RateLimiter limiter;
    private final RequestProcessor processor;
    private final DeliveryLogger log;
    private final int timeoutSeconds;
    private final int maxBodyBytes;

    private HttpServer server;
    private ExecutorService pool;

    public ApiServer(String host, int port, Authentication auth, RateLimiter limiter, RequestProcessor processor,
                     DeliveryLogger log, int timeoutSeconds, int maxBodyBytes) {
        this.host = host;
        this.port = port;
        this.auth = auth;
        this.limiter = limiter;
        this.processor = processor;
        this.log = log;
        this.timeoutSeconds = timeoutSeconds;
        this.maxBodyBytes = maxBodyBytes;
    }

    public void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(host, port), 0);
        AtomicInteger n = new AtomicInteger();
        pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "DiscordKitBridge-Http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    /** The port actually bound (useful when configured with 0). */
    public int boundPort() {
        return server.getAddress().getPort();
    }

    public void stop() {
        if (server != null) server.stop(0);
        if (pool != null) pool.shutdownNow();
    }

    // ------------------------------------------------------------------ handling

    private void handle(HttpExchange ex) throws IOException {
        Response resp;
        try {
            resp = route(ex);
        } catch (Throwable t) {
            log.log("ERROR", "where", "http", "error", t);
            resp = ResponseHandler.error(Status.INTERNAL_ERROR, "Internal error");
        }
        byte[] body = resp.body().getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        ex.sendResponseHeaders(resp.http(), body.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(body);
        }
    }

    private Response route(HttpExchange ex) throws IOException {
        String ip = ex.getRemoteAddress().getAddress().getHostAddress();
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();

        if (!limiter.tryAcquire(ip)) {
            log.log("RATE_LIMITED", "ip", ip, "path", path);
            return ResponseHandler.error(Status.RATE_LIMITED, null);
        }

        if (path.equals(BASE + "/health")) {
            return method.equals("GET") ? ResponseHandler.health() : ResponseHandler.error(Status.METHOD_NOT_ALLOWED, null);
        }

        if (!auth.verify(ex.getRequestHeaders().getFirst("Authorization"))) {
            log.log("AUTH_ERROR", "ip", ip, "path", path);
            return ResponseHandler.error(Status.UNAUTHORIZED, null);
        }

        if (path.equals(BASE + "/kits/request")) {
            if (!method.equals("POST")) return ResponseHandler.error(Status.METHOD_NOT_ALLOWED, null);
            return handleRequest(ex);
        }
        if (path.equals(BASE + "/kits")) {
            if (!method.equals("GET")) return ResponseHandler.error(Status.METHOD_NOT_ALLOWED, null);
            return ResponseHandler.kits(processor.kits());
        }
        if (path.startsWith(BASE + "/kits/status/")) {
            if (!method.equals("GET")) return ResponseHandler.error(Status.METHOD_NOT_ALLOWED, null);
            String id = path.substring((BASE + "/kits/status/").length());
            if (!Validators.isValidRequestId(id)) return ResponseHandler.error(Status.INVALID_REQUEST, "Invalid request id");
            Optional<RequestRecord> r = processor.status(id);
            return r.map(ResponseHandler::statusOf)
                    .orElseGet(() -> ResponseHandler.error(Status.NOT_FOUND, "Unknown request id", id));
        }
        return ResponseHandler.error(Status.NOT_FOUND, null);
    }

    private Response handleRequest(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(maxBodyBytes + 1);
        if (raw.length > maxBodyBytes) return ResponseHandler.error(Status.PAYLOAD_TOO_LARGE, null);

        KitRequest req;
        try {
            Map<String, Object> body = Json.parseObject(new String(raw, StandardCharsets.UTF_8));
            req = KitRequest.parse(body);
        } catch (Json.JsonException e) {
            return ResponseHandler.error(Status.INVALID_REQUEST, "Body is not valid JSON: " + e.getMessage());
        } catch (KitRequest.InvalidRequestException e) {
            log.log("INVALID_REQUEST", "request_id", e.requestId(), "reason", e.getMessage());
            return ResponseHandler.error(Status.INVALID_REQUEST, e.getMessage(), e.requestId());
        }

        try {
            RequestRecord done = processor.submit(req).get(timeoutSeconds + 2L, TimeUnit.SECONDS);
            return ResponseHandler.result(done);
        } catch (TimeoutException e) {
            return ResponseHandler.error(Status.TIMEOUT, null, req.requestId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseHandler.error(Status.INTERNAL_ERROR, null, req.requestId());
        } catch (ExecutionException e) {
            log.log("ERROR", "request_id", req.requestId(), "error", e.getCause());
            return ResponseHandler.error(Status.INTERNAL_ERROR, null, req.requestId());
        }
    }
}
