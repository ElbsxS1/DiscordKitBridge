package br.asthen.kitbridge.util;

import java.util.OptionalLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reais in, cents out. Money never goes through floating point on the way in. */
public final class Prices {

    /** Up to 6 integer digits (R$ 999.999,99) and at most 2 decimals, with "," or "." as separator. */
    private static final Pattern P = Pattern.compile("^(\\d{1,6})(?:[.,](\\d{1,2}))?$");

    private Prices() {
    }

    /** @return the price in cents, or empty when the text is not a valid price greater than zero */
    public static OptionalLong parse(String text) {
        if (text == null) return OptionalLong.empty();
        Matcher m = P.matcher(text.strip());
        if (!m.matches()) return OptionalLong.empty();
        long reais = Long.parseLong(m.group(1));
        String dec = m.group(2) == null ? "00" : (m.group(2) + "0").substring(0, 2); // "5" means 50 cents
        long cents = reais * 100 + Long.parseLong(dec);
        return cents > 0 ? OptionalLong.of(cents) : OptionalLong.empty();
    }

    /** 2990 -> "R$ 29,90" */
    public static String format(long cents) {
        return String.format("R$ %d,%02d", cents / 100, cents % 100);
    }
}
