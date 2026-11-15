package br.asthen.kitbridge.kit;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A stored kit. Every item is kept as the opaque byte blob produced by Paper's
 * {@code ItemStack#serializeAsBytes()}, which carries the item's data version, so
 * enchantments, names, lore, durability and all data components survive restarts and
 * server upgrades without this class knowing anything about them.
 *
 * <p>{@code priceCents} is the sale price in cents (R$ 29,90 = 2990), or {@link #NO_PRICE}.
 * {@code box} is the Shulker Box material the kit is delivered in (e.g. "BLUE_SHULKER_BOX"), or null
 * for the default one. Both are optional in the file, so kits saved by older versions keep loading.
 */
public record KitData(String name, long createdAt, String createdBy, boolean enabled, List<Slot> slots,
                      long priceCents, String box) {

    public static final int FORMAT = 1;
    public static final long NO_PRICE = -1L;
    private static final Pattern BOX = Pattern.compile("^[A-Z_]{1,40}$");

    public record Slot(int slot, byte[] data) {
    }

    public KitData {
        slots = List.copyOf(slots);
    }

    /** Kit without a price and with the default box. */
    public KitData(String name, long createdAt, String createdBy, boolean enabled, List<Slot> slots) {
        this(name, createdAt, createdBy, enabled, slots, NO_PRICE, null);
    }

    public int itemCount() {
        return slots.size();
    }

    public boolean hasPrice() {
        return priceCents > 0;
    }

    public KitData withEnabled(boolean value) {
        return new KitData(name, createdAt, createdBy, value, slots, priceCents, box);
    }

    public KitData withPrice(long cents) {
        return new KitData(name, createdAt, createdBy, enabled, slots, cents, box);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", FORMAT);
        m.put("name", name);
        m.put("created_at", createdAt);
        m.put("created_by", createdBy);
        m.put("enabled", enabled);
        if (hasPrice()) m.put("price_cents", priceCents);
        if (box != null) m.put("box", box);
        List<Object> items = new ArrayList<>();
        for (Slot s : slots) {
            Map<String, Object> im = new LinkedHashMap<>();
            im.put("slot", s.slot());
            im.put("data", Base64.getEncoder().encodeToString(s.data()));
            items.add(im);
        }
        m.put("items", items);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static KitData fromMap(Map<String, Object> m) {
        try {
            long format = ((Number) m.getOrDefault("format", (long) FORMAT)).longValue();
            if (format != FORMAT) throw new IllegalArgumentException("Unsupported kit format " + format);
            String name = (String) m.get("name");
            if (name == null) throw new IllegalArgumentException("Missing name");
            long createdAt = ((Number) m.getOrDefault("created_at", 0L)).longValue();
            String createdBy = String.valueOf(m.getOrDefault("created_by", "unknown"));
            boolean enabled = !(m.get("enabled") instanceof Boolean b) || b;
            long price = m.get("price_cents") instanceof Number n && n.longValue() > 0 ? n.longValue() : NO_PRICE;
            String box = m.get("box") instanceof String s && BOX.matcher(s).matches() ? s : null;
            List<Slot> slots = new ArrayList<>();
            for (Object o : (List<Object>) m.get("items")) {
                Map<String, Object> im = (Map<String, Object>) o;
                int slot = ((Number) im.get("slot")).intValue();
                byte[] data = Base64.getDecoder().decode((String) im.get("data"));
                slots.add(new Slot(slot, data));
            }
            return new KitData(name, createdAt, createdBy, enabled, slots, price, box);
        } catch (ClassCastException | NullPointerException e) {
            throw new IllegalArgumentException("Malformed kit file", e);
        }
    }
}
