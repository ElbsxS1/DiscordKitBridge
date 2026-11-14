package br.asthen.kitbridge.kit;

import br.asthen.kitbridge.security.Validators;
import br.asthen.kitbridge.util.Atomic;
import br.asthen.kitbridge.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** One JSON file per kit under {@code kits/}. Writes are atomic; unreadable files are skipped, never deleted. */
public final class KitStorage {

    private final Path dir;
    private final Consumer<String> warn;

    public KitStorage(Path dir, Consumer<String> warn) {
        this.dir = dir;
        this.warn = warn;
    }

    /** @return kits keyed by lower-cased name */
    public Map<String, KitData> loadAll() throws IOException {
        Files.createDirectories(dir);
        Map<String, KitData> out = new LinkedHashMap<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                String file = p.getFileName().toString();
                String base = file.substring(0, file.length() - 5);
                try {
                    KitData k = KitData.fromMap(Json.parseObject(Files.readString(p, StandardCharsets.UTF_8)));
                    if (!k.name().equals(base) || !Validators.isValidKitName(k.name())) {
                        warn.accept("Kit file " + file + " ignored: its name does not match the file name");
                        continue;
                    }
                    KitData previous = out.put(k.name().toLowerCase(Locale.ROOT), k);
                    if (previous != null) warn.accept("Kits '" + previous.name() + "' and '" + k.name() + "' differ only by case");
                } catch (Exception e) {
                    warn.accept("Kit file " + file + " could not be loaded: " + e.getMessage());
                }
            }
        }
        return out;
    }

    public void save(KitData kit) throws IOException {
        Atomic.write(fileFor(kit.name()), Json.pretty(kit.toMap()));
    }

    public boolean delete(String name) throws IOException {
        return Files.deleteIfExists(fileFor(name));
    }

    private Path fileFor(String name) throws IOException {
        if (!Validators.isValidKitName(name)) throw new IOException("Invalid kit name");
        return dir.resolve(name + ".json");
    }
}
