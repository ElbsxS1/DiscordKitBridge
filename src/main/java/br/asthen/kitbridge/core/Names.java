package br.asthen.kitbridge.core;

import java.util.Locale;

public final class Names {
    private Names() {
    }

    /**
     * Canonical form used to compare player names across platforms: lower case, Floodgate
     * prefix removed, spaces turned into underscores (Floodgate does the same to gamertags).
     */
    public static String normalize(String name, String prefix) {
        String n = name.strip().toLowerCase(Locale.ROOT);
        if (prefix != null && !prefix.isEmpty() && n.startsWith(prefix.toLowerCase(Locale.ROOT))) {
            n = n.substring(prefix.length());
        }
        return n.replace(' ', '_');
    }
}
