package br.asthen.kitbridge.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The life of one request: current status plus the history of every step. Thread-safe. */
public final class RequestRecord {

    private final String requestId;
    private final String player;
    private final Platform platform;
    private final String kit;
    private final long createdAt;
    private Status status;
    private String message;
    private long updatedAt;
    private final List<long[]> historyAt = new ArrayList<>();
    private final List<Status> historyStatus = new ArrayList<>();

    public RequestRecord(KitRequest r, long now) {
        this(r.requestId(), r.player(), r.platform(), r.kit(), now, Status.QUEUED, Status.QUEUED.defaultMessage(), now);
        historyStatus.add(Status.QUEUED);
        historyAt.add(new long[]{now});
    }

    RequestRecord(String requestId, String player, Platform platform, String kit, long createdAt,
                  Status status, String message, long updatedAt) {
        this.requestId = requestId;
        this.player = player;
        this.platform = platform;
        this.kit = kit;
        this.createdAt = createdAt;
        this.status = status;
        this.message = message;
        this.updatedAt = updatedAt;
    }

    public String requestId() { return requestId; }
    public String player() { return player; }
    public Platform platform() { return platform; }
    public String kit() { return kit; }
    public synchronized Status status() { return status; }
    public synchronized String message() { return message; }
    public synchronized long updatedAt() { return updatedAt; }
    public long createdAt() { return createdAt; }

    public synchronized void set(Status s, String msg, long now) {
        this.status = s;
        this.message = msg != null ? msg : s.defaultMessage();
        this.updatedAt = now;
        historyStatus.add(s);
        historyAt.add(new long[]{now});
    }

    public synchronized Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", status.isSuccess());
        m.put("status", status.name());
        m.put("final", status.isTerminal());
        m.put("request_id", requestId);
        m.put("player", player);
        m.put("platform", platform.id());
        m.put("kit", kit);
        m.put("message", message);
        m.put("created_at", createdAt);
        m.put("updated_at", updatedAt);
        List<Object> h = new ArrayList<>();
        for (int i = 0; i < historyStatus.size(); i++) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("status", historyStatus.get(i).name());
            e.put("at", historyAt.get(i)[0]);
            h.add(e);
        }
        m.put("history", h);
        return m;
    }

    @SuppressWarnings("unchecked")
    static RequestRecord fromMap(Map<String, Object> m) {
        Status st = Status.valueOf((String) m.get("status"));
        RequestRecord r = new RequestRecord((String) m.get("request_id"), (String) m.get("player"),
                Platform.parse((String) m.get("platform")), (String) m.get("kit"),
                ((Number) m.get("created_at")).longValue(), st, (String) m.get("message"),
                ((Number) m.get("updated_at")).longValue());
        for (Object o : (List<Object>) m.get("history")) {
            Map<String, Object> e = (Map<String, Object>) o;
            r.historyStatus.add(Status.valueOf((String) e.get("status")));
            r.historyAt.add(new long[]{((Number) e.get("at")).longValue()});
        }
        return r;
    }
}
