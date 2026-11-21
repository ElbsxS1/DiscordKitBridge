package br.asthen.kitbridge.core;

import java.util.Set;

/**
 * The {@code starter-kit} section of config.yml.
 *
 * @param enabled          master switch for every automatic delivery
 * @param name             kit that is delivered (register it with /dk create Kit_Inicial, holding a Shulker Box)
 * @param cooldownSeconds  a player never gets it twice within this time, whatever the trigger
 * @param onJoin           deliver when the player joins the server
 * @param joinSkipWorlds   on join, do nothing if the player is in one of these worlds (the lobby: they get it when
 *                         they enter survival through the NPC instead)
 * @param onDeath          deliver after the player respawns from a death
 * @param onEnterWorld     deliver when the player goes from {@code enterFrom} to {@code enterTo} (lobby NPC)
 * @param firstTimeOnly   join / entering survival only give the kit to a player who never received it (saved on disk);
 *                         dying gives it again whenever {@code onDeath} is on
 * @param dropIfFull       what does not fit in the inventory is dropped at the player's feet (only they can pick it
 *                         up) instead of being lost
 * @param message          chat message (& colour codes); empty = silent
 */
public record StarterKitSettings(boolean enabled, String name, int cooldownSeconds, boolean onJoin,
                                 Set<String> joinSkipWorlds, boolean onDeath, boolean onEnterWorld,
                                 String enterFrom, String enterTo, boolean firstTimeOnly, boolean dropIfFull,
                                 String message) {

    public StarterKitSettings {
        name = (name == null || name.isBlank()) ? StarterKitPolicy.DEFAULT_NAME : name.strip();
        joinSkipWorlds = Set.copyOf(joinSkipWorlds);
        cooldownSeconds = Math.max(0, cooldownSeconds);
        enterFrom = enterFrom == null ? "" : enterFrom.strip();
        enterTo = enterTo == null ? "" : enterTo.strip();
        message = message == null ? "" : message;
    }

    /** True for a move from the lobby world to the survival world (names compared ignoring case). */
    public boolean matchesEnter(String fromWorld, String toWorld) {
        return onEnterWorld && !enterFrom.isEmpty() && !enterTo.isEmpty()
                && enterFrom.equalsIgnoreCase(fromWorld) && enterTo.equalsIgnoreCase(toWorld);
    }
}
