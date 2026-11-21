package br.asthen.kitbridge.core;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rules of the Kit Inicial that do not depend on Paper (so they are covered by the JDK-only test suite):
 * which kit name is the starter kit, and the per-player cooldown that stops the same player from receiving it twice
 * when two triggers fire together (for example dying in the Nether and respawning in the Overworld, or joining and
 * clicking the lobby NPC right away).
 */
public final class StarterKitPolicy {

    public static final String DEFAULT_NAME = "Kit_Inicial";

    private final Map<UUID, Long> lastGiven = new ConcurrentHashMap<>();

    /** True when {@code kitName} is the starter kit. Names are compared ignoring case, like every kit name. */
    public static boolean isStarter(String kitName, String starterName) {
        if (kitName == null) return false;
        String s = (starterName == null || starterName.isBlank()) ? DEFAULT_NAME : starterName.strip();
        return kitName.strip().equalsIgnoreCase(s);
    }

    /** True when {@code worldName} is in {@code worlds} (ignoring case). */
    public static boolean inWorlds(String worldName, Set<String> worlds) {
        if (worldName == null) return false;
        String w = worldName.toLowerCase(Locale.ROOT);
        for (String x : worlds) if (x != null && x.toLowerCase(Locale.ROOT).equals(w)) return true;
        return false;
    }

    /**
     * Atomically checks the cooldown and, when it has passed, starts a new one.
     *
     * @return true if the player may receive the kit now; false if they got it less than {@code cooldownMs} ago
     */
    public boolean tryAcquire(UUID player, long nowMs, long cooldownMs) {
        boolean[] ok = {false};
        lastGiven.compute(player, (id, last) -> {
            if (last == null || cooldownMs <= 0 || nowMs - last >= cooldownMs || nowMs < last) {
                ok[0] = true;
                return nowMs;
            }
            return last;
        });
        return ok[0];
    }

    /** Gives the cooldown back (the delivery failed, so the player did not actually receive anything). */
    public void release(UUID player) {
        lastGiven.remove(player);
    }

    public void forget(UUID player) {
        lastGiven.remove(player);
    }

    public void clear() {
        lastGiven.clear();
    }
}
