package br.asthen.kitbridge.core;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A later change to a delivery the bot already knows about (for example KIT_PENDING -> KIT_DELIVERED).
 * The id is derived from the request id and the new status, so the same fact always has the same id and
 * the bot can ignore repeats.
 */
public record DeliveryEvent(String requestId, String status, String player, String kit, long at) {

    public String eventId() {
        return requestId + ":" + status;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("event_id", eventId());
        m.put("request_id", requestId);
        m.put("status", status);
        m.put("final", true);
        m.put("success", true);
        if (player != null) m.put("player", player);
        if (kit != null) m.put("kit", kit);
        m.put("at", at);
        return m;
    }
}
