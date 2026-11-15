package br.asthen.kitbridge.api;

import br.asthen.kitbridge.core.RequestRecord;
import br.asthen.kitbridge.core.Status;
import br.asthen.kitbridge.kit.KitData;
import br.asthen.kitbridge.util.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Builds every JSON body the API sends, so all responses share one shape. */
public final class ResponseHandler {

    public record Response(int http, String body) {
    }

    private ResponseHandler() {
    }

    /** Final result of a request: HTTP code follows the status (200 delivered, 404 offline, ...). */
    public static Response result(RequestRecord r) {
        return new Response(r.status().isTerminal() ? r.status().http() : 200, Json.write(r.toMap()));
    }

    /** A status lookup always answers 200 when the request exists, whatever its outcome. */
    public static Response statusOf(RequestRecord r) {
        return new Response(200, Json.write(r.toMap()));
    }

    public static Response error(Status s, String message) {
        return error(s, message, null);
    }

    public static Response error(Status s, String message, String requestId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", false);
        m.put("status", s.name());
        m.put("final", true);
        if (requestId != null) m.put("request_id", requestId);
        m.put("message", message != null ? message : s.defaultMessage());
        return new Response(s.http(), Json.write(m));
    }

    public static Response health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("status", Status.OK.name());
        m.put("message", "DiscordKitBridge is running");
        return new Response(200, Json.write(m));
    }

    public static Response kits(List<KitData> kits) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("success", true);
        m.put("status", Status.OK.name());
        m.put("kits", kitList(kits));
        return new Response(200, Json.write(m));
    }

    public static List<Object> kitList(List<KitData> kits) {
        List<Object> arr = new ArrayList<>();
        for (KitData k : kits) {
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("name", k.name());
            e.put("enabled", k.enabled());
            e.put("items", k.itemCount());
            e.put("price", k.hasPrice() ? (Object) (k.priceCents() / 100.0) : null);
            e.put("price_cents", k.hasPrice() ? (Object) k.priceCents() : null);
            e.put("for_sale", k.enabled() && k.hasPrice()); // enabled AND priced; otherwise show as unavailable
            arr.add(e);
        }
        return arr;
    }
}
