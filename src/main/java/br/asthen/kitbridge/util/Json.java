package br.asthen.kitbridge.util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal, dependency-free JSON parser/writer (RFC 8259 subset).
 * Objects become LinkedHashMap, arrays become ArrayList, numbers become Long or Double.
 */
public final class Json {

    public static final class JsonException extends Exception {
        public JsonException(String message) {
            super(message);
        }
    }

    private static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
    }

    // ---------------------------------------------------------------- parse

    public static Object parse(String text) throws JsonException {
        if (text == null) throw new JsonException("Empty body");
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.err("Unexpected trailing data");
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) throws JsonException {
        Object o = parse(text);
        if (!(o instanceof Map)) throw new JsonException("Expected a JSON object");
        return (Map<String, Object>) o;
    }

    private JsonException err(String msg) {
        return new JsonException(msg + " at position " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Object value(int depth) throws JsonException {
        if (depth > MAX_DEPTH) throw err("Nesting too deep");
        if (i >= s.length()) throw err("Unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{':
                return object(depth);
            case '[':
                return array(depth);
            case '"':
                return string();
            case 't':
                return literal("true", Boolean.TRUE);
            case 'f':
                return literal("false", Boolean.FALSE);
            case 'n':
                return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw err("Unexpected character '" + c + "'");
        }
    }

    private Object literal(String word, Object v) throws JsonException {
        if (!s.startsWith(word, i)) throw err("Invalid literal");
        i += word.length();
        return v;
    }

    private Map<String, Object> object(int depth) throws JsonException {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; // {
        ws();
        if (i < s.length() && s.charAt(i) == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (i >= s.length() || s.charAt(i) != '"') throw err("Expected string key");
            String k = string();
            ws();
            if (i >= s.length() || s.charAt(i) != ':') throw err("Expected ':'");
            i++;
            ws();
            m.put(k, value(depth + 1));
            ws();
            if (i >= s.length()) throw err("Unexpected end");
            char c = s.charAt(i++);
            if (c == '}') return m;
            if (c != ',') throw err("Expected ',' or '}'");
        }
    }

    private List<Object> array(int depth) throws JsonException {
        List<Object> l = new ArrayList<>();
        i++; // [
        ws();
        if (i < s.length() && s.charAt(i) == ']') {
            i++;
            return l;
        }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (i >= s.length()) throw err("Unexpected end");
            char c = s.charAt(i++);
            if (c == ']') return l;
            if (c != ',') throw err("Expected ',' or ']'");
        }
    }

    private String string() throws JsonException {
        StringBuilder sb = new StringBuilder();
        i++; // opening quote
        while (true) {
            if (i >= s.length()) throw err("Unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return sb.toString();
            if (c < 0x20) throw err("Control character in string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) throw err("Bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw err("Bad unicode escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("Bad unicode escape");
                    }
                    i += 4;
                    break;
                default:
                    throw err("Bad escape");
            }
        }
    }

    private Object number() throws JsonException {
        int start = i;
        if (s.charAt(i) == '-') i++;
        boolean isDouble = false;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') {
                i++;
            } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                isDouble = true;
                i++;
            } else {
                break;
            }
        }
        String t = s.substring(start, i);
        try {
            if (!isDouble) return Long.parseLong(t);
            return Double.parseDouble(t);
        } catch (NumberFormatException ex) {
            throw err("Invalid number");
        }
    }

    // ---------------------------------------------------------------- write

    public static String write(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, -1, 0);
        return sb.toString();
    }

    public static String pretty(Object o) {
        StringBuilder sb = new StringBuilder();
        write(sb, o, 2, 0);
        sb.append('\n');
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object o, int indent, int level) {
        if (o == null) {
            sb.append("null");
        } else if (o instanceof String str) {
            quote(sb, str);
        } else if (o instanceof Boolean || o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            sb.append(o);
        } else if (o instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) sb.append("null");
            else sb.append(n);
        } else if (o instanceof Map<?, ?> m) {
            if (m.isEmpty()) {
                sb.append("{}");
                return;
            }
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                quote(sb, String.valueOf(e.getKey()));
                sb.append(indent >= 0 ? ": " : ":");
                write(sb, e.getValue(), indent, level + 1);
            }
            newline(sb, indent, level);
            sb.append('}');
        } else if (o instanceof Iterable<?> it) {
            boolean first = true;
            sb.append('[');
            for (Object v : it) {
                if (!first) sb.append(',');
                first = false;
                newline(sb, indent, level + 1);
                write(sb, v, indent, level + 1);
            }
            if (!first) newline(sb, indent, level);
            sb.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON type: " + o.getClass());
        }
    }

    private static void newline(StringBuilder sb, int indent, int level) {
        if (indent < 0) return;
        sb.append('\n');
        for (int k = 0; k < indent * level; k++) sb.append(' ');
    }

    private static void quote(StringBuilder sb, String str) {
        sb.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
