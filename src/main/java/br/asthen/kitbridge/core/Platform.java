package br.asthen.kitbridge.core;

import java.util.Locale;

public enum Platform {
    BEDROCK, JAVA;

    /** @return null when the text is not exactly "bedrock" or "java" (case-insensitive) */
    public static Platform parse(String s) {
        if (s == null) return null;
        return switch (s.strip().toLowerCase(Locale.ROOT)) {
            case "bedrock" -> BEDROCK;
            case "java" -> JAVA;
            default -> null;
        };
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }
}
