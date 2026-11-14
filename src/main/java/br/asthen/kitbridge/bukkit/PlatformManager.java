package br.asthen.kitbridge.bukkit;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * Asks Floodgate whether a player joined through Bedrock. Floodgate is reached by reflection
 * so the plugin has no compile-time dependency on it and works with or without it installed.
 * The name alone is never trusted: Floodgate's own record of the connection is.
 */
public final class PlatformManager {

    private Object api;
    private Method isFloodgatePlayer;

    /** @return true if Floodgate is installed and ready */
    public synchronized boolean available() {
        if (api != null) return true;
        try {
            Class<?> c = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
            Object instance = c.getMethod("getInstance").invoke(null);
            if (instance == null) return false;
            isFloodgatePlayer = c.getMethod("isFloodgatePlayer", UUID.class);
            api = instance;
            return true;
        } catch (ReflectiveOperationException | LinkageError e) {
            return false;
        }
    }

    /** @return TRUE for a Bedrock (Floodgate) player, FALSE for Java, null when Floodgate is unavailable */
    public Boolean isBedrock(UUID uuid) {
        if (!available()) return null;
        try {
            return (Boolean) isFloodgatePlayer.invoke(api, uuid);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }
}
