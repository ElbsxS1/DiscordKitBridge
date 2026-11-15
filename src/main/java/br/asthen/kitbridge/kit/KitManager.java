package br.asthen.kitbridge.kit;

import br.asthen.kitbridge.core.KitCatalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** In-memory index over {@link KitStorage}. Names are unique ignoring case. */
public final class KitManager implements KitCatalog {

    public static final class KitException extends Exception {
        public KitException(String message) {
            super(message);
        }
    }

    private final KitStorage storage;
    private final Runnable onChange;
    private final Map<String, KitData> kits = new ConcurrentHashMap<>();

    public KitManager(KitStorage storage, Runnable onChange) {
        this.storage = storage;
        this.onChange = onChange;
    }

    /** Re-reads every kit from disk. @return number of kits loaded */
    public synchronized int reload() throws IOException {
        Map<String, KitData> fresh = storage.loadAll();
        kits.clear();
        kits.putAll(fresh);
        onChange.run();
        return kits.size();
    }

    @Override
    public Optional<KitData> get(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(kits.get(name.toLowerCase(Locale.ROOT)));
    }

    public List<KitData> list() {
        List<KitData> l = new ArrayList<>(kits.values());
        l.sort(Comparator.comparing(k -> k.name().toLowerCase(Locale.ROOT)));
        return l;
    }

    public synchronized void create(KitData kit) throws KitException, IOException {
        String key = kit.name().toLowerCase(Locale.ROOT);
        if (kits.containsKey(key)) throw new KitException("Já existe um kit chamado " + kits.get(key).name() + ".");
        storage.save(kit);
        kits.put(key, kit);
        onChange.run();
    }

    /** @return the removed kit, if it existed */
    public synchronized Optional<KitData> delete(String name) throws IOException {
        KitData k = kits.get(name.toLowerCase(Locale.ROOT));
        if (k == null) return Optional.empty();
        storage.delete(k.name());
        kits.remove(name.toLowerCase(Locale.ROOT));
        onChange.run();
        return Optional.of(k);
    }

    public synchronized Optional<KitData> setEnabled(String name, boolean enabled) throws IOException {
        KitData k = kits.get(name.toLowerCase(Locale.ROOT));
        if (k == null) return Optional.empty();
        KitData updated = k.withEnabled(enabled);
        storage.save(updated);
        kits.put(name.toLowerCase(Locale.ROOT), updated);
        onChange.run();
        return Optional.of(updated);
    }

    /** @param cents the new price in cents, or {@link KitData#NO_PRICE} to take the kit off sale */
    public synchronized Optional<KitData> setPrice(String name, long cents) throws IOException {
        KitData k = kits.get(name.toLowerCase(Locale.ROOT));
        if (k == null) return Optional.empty();
        KitData updated = k.withPrice(cents);
        storage.save(updated);
        kits.put(name.toLowerCase(Locale.ROOT), updated);
        onChange.run();
        return Optional.of(updated);
    }
}
