package br.asthen.kitbridge.core;

import br.asthen.kitbridge.kit.KitData;

import java.util.Optional;

public interface KitCatalog {
    Optional<KitData> get(String name);
}
