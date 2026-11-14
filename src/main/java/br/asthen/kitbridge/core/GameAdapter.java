package br.asthen.kitbridge.core;

import br.asthen.kitbridge.kit.KitData;

/** Everything the delivery logic needs from the game. All methods are called on the server thread. */
public interface GameAdapter {

    enum Outcome { ONLINE, OFFLINE, MISMATCH, UNVERIFIABLE }

    record Lookup(Outcome outcome, PlayerRef player, Platform actualPlatform) {
        public static Lookup of(Outcome o) { return new Lookup(o, null, null); }
    }

    enum GiveResult { DELIVERED, FULL, CORRUPT, OFFLINE }

    /** Finds an online player and verifies the platform. */
    Lookup find(String name, Platform platform);

    /** All-or-nothing: gives the whole kit, or nothing at all. */
    GiveResult give(PlayerRef player, KitData kit);

    void tell(PlayerRef player, String message);
}
