package br.asthen.kitbridge.kit;

import br.asthen.kitbridge.util.Atomic;
import br.asthen.kitbridge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Kits waiting for a player (full inventory, or offline when require-online is false).
 * Each entry holds a full copy of the kit as it was when requested, so a later edit or
 * deletion of the kit cannot change what the player was promised.
 * Keys: "uuid:&lt;uuid&gt;" or "name:&lt;platform&gt;:&lt;normalised-name&gt;".
 */
public final class PendingStore {

    public record Entry(String requestId, String kitName, long createdAt, KitData kit) {
    }

    private final Path dir;

    public PendingStore(Path dir) {
        this.dir = dir;
    }

    public synchronized void add(String key, Entry e) throws IOException {
        List<Entry> l = new ArrayList<>(list(key));
        for (Entry x : l) if (x.requestId().equals(e.requestId())) return; // already queued
        l.add(e);
        write(key, l);
    }

    public synchronized List<Entry> list(String key) {
        Path f = file(key);
        if (!Files.exists(f)) return List.of();
        try {
            Map<String, Object> root = Json.parseObject(Files.readString(f, StandardCharsets.UTF_8));
            List<Entry> out = new ArrayList<>();
            for (Object o : (List<?>) root.get("entries")) {
                @SuppressWarnings("unchecked")
                Map<String, Object> m = (Map<String, Object>) o;
                @SuppressWarnings("unchecked")
                KitData kit = KitData.fromMap((Map<String, Object>) m.get("kit"));
                out.add(new Entry((String) m.get("request_id"), (String) m.get("kit_name"),
                        ((Number) m.get("created_at")).longValue(), kit));
            }
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }

    public synchronized void remove(String key, String requestId) throws IOException {
        List<Entry> l = new ArrayList<>(list(key));
        l.removeIf(e -> e.requestId().equals(requestId));
        if (l.isEmpty()) Files.deleteIfExists(file(key));
        else write(key, l);
    }

    public synchronized int count(String key) {
        return list(key).size();
    }

    private void write(String key, List<Entry> l) throws IOException {
        List<Object> arr = new ArrayList<>();
        for (Entry e : l) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("request_id", e.requestId());
            m.put("kit_name", e.kitName());
            m.put("created_at", e.createdAt());
            m.put("kit", e.kit().toMap());
            arr.add(m);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", arr);
        Atomic.write(file(key), Json.write(root));
    }

    private Path file(String key) {
        return dir.resolve(key.replaceAll("[^A-Za-z0-9_.-]", "_") + ".json");
    }
}
