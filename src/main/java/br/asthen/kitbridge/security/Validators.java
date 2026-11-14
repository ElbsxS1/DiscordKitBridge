package br.asthen.kitbridge.security;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Strict allow-list validation for every value that comes from outside the plugin. */
public final class Validators {

    private static final Pattern KIT = Pattern.compile("^[A-Za-z0-9_-]{1,32}$");
    private static final Pattern REQUEST_ID = Pattern.compile("^[A-Za-z0-9_.:-]{1,64}$");
    /** Java names: [A-Za-z0-9_]{3,16}. Bedrock gamertags may contain spaces. An optional leading prefix is allowed. */
    private static final Pattern PLAYER = Pattern.compile("^[A-Za-z0-9_. ]{1,32}$");

    /** Names Windows refuses as file names; a kit becomes a file, so they are refused everywhere. */
    private static final Set<String> RESERVED = Set.of(
            "con", "prn", "aux", "nul",
            "com1", "com2", "com3", "com4", "com5", "com6", "com7", "com8", "com9",
            "lpt1", "lpt2", "lpt3", "lpt4", "lpt5", "lpt6", "lpt7", "lpt8", "lpt9");

    private Validators() {
    }

    public static boolean isValidKitName(String s) {
        return s != null && KIT.matcher(s).matches() && !RESERVED.contains(s.toLowerCase(Locale.ROOT));
    }

    public static boolean isValidRequestId(String s) {
        return s != null && REQUEST_ID.matcher(s).matches();
    }

    public static boolean isValidPlayerName(String s) {
        return s != null && !s.isBlank() && PLAYER.matcher(s).matches() && !s.startsWith(" ") && !s.endsWith(" ");
    }
}
