package rs.realm.launcher;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small recursive-descent JSON reader and string writer.
 *
 * The launcher is deliberately dependency-free — it is a jar players download and run with a plain
 * JRE, and every dependency is another thing to bundle, sign and keep current. Talking to the
 * backend needs JSON, so it lives here rather than pulling in Jackson for two request shapes.
 *
 * Reading is permissive in the one way that matters: {@link #str} and friends return a default when
 * a field is missing or the wrong type, so a backend that grows a field never breaks an old
 * launcher, and one that drops a field degrades instead of throwing on a player's machine.
 */
public final class Json {

    private final String src;
    private int pos;

    private Json(String src) {
        this.src = src;
    }

    /** Parses a document into Map / List / String / Double / Boolean / null. */
    public static Object parse(String text) {
        Json json = new Json(text);
        json.skipWhitespace();
        Object value = json.readValue();
        return value;
    }

    /** Parses a document expected to be an object; an empty map if it is anything else. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    // -------------------------------------------------------------------- accessors ----

    public static String str(Map<String, Object> obj, String key, String fallback) {
        Object value = obj == null ? null : obj.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public static boolean bool(Map<String, Object> obj, String key, boolean fallback) {
        Object value = obj == null ? null : obj.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public static int number(Map<String, Object> obj, String key, int fallback) {
        Object value = obj == null ? null : obj.get(key);
        return value instanceof Double ? (int) (double) (Double) value : fallback;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Map<String, Object> obj, String key) {
        Object value = obj == null ? null : obj.get(key);
        return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    /** The array at {@code key}, keeping only its object entries. */
    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Map<String, Object> obj, String key) {
        List<Map<String, Object>> out = new ArrayList<>();
        Object value = obj == null ? null : obj.get(key);
        if (!(value instanceof List)) {
            return out;
        }
        for (Object entry : (List<Object>) value) {
            if (entry instanceof Map) {
                out.add((Map<String, Object>) entry);
            }
        }
        return out;
    }

    // ---------------------------------------------------------------------- writing ----

    /** Serialises a flat string map. Every request this launcher sends is that shape. */
    public static String write(Map<String, String> values) {
        StringBuilder sb = new StringBuilder("{");
        boolean first = true;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            if (!first) {
                sb.append(',');
            }
            first = false;
            sb.append(quote(entry.getKey())).append(':').append(quote(entry.getValue()));
        }
        return sb.append('}').toString();
    }

    private static String quote(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    // ---------------------------------------------------------------------- parsing ----

    private Object readValue() {
        if (pos >= src.length()) {
            return null;
        }
        char c = src.charAt(pos);
        return switch (c) {
            case '{' -> readObject();
            case '[' -> readArray();
            case '"' -> readString();
            case 't' -> readLiteral("true", Boolean.TRUE);
            case 'f' -> readLiteral("false", Boolean.FALSE);
            case 'n' -> readLiteral("null", null);
            default -> readNumber();
        };
    }

    private Map<String, Object> readObject() {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++; // '{'
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (pos < src.length()) {
            skipWhitespace();
            String key = readString();
            skipWhitespace();
            if (peek() == ':') {
                pos++;
            }
            skipWhitespace();
            map.put(key, readValue());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == '}' || c == '\0') {
                break;
            }
        }
        return map;
    }

    private List<Object> readArray() {
        List<Object> list = new ArrayList<>();
        pos++; // '['
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (pos < src.length()) {
            skipWhitespace();
            list.add(readValue());
            skipWhitespace();
            char c = peek();
            pos++;
            if (c == ']' || c == '\0') {
                break;
            }
        }
        return list;
    }

    private String readString() {
        if (peek() != '"') {
            return "";
        }
        pos++;
        StringBuilder sb = new StringBuilder();
        while (pos < src.length()) {
            char c = src.charAt(pos++);
            if (c == '"') {
                break;
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (pos >= src.length()) {
                break;
            }
            char escape = src.charAt(pos++);
            switch (escape) {
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'u' -> {
                    if (pos + 4 <= src.length()) {
                        sb.append((char) Integer.parseInt(src.substring(pos, pos + 4), 16));
                        pos += 4;
                    }
                }
                default -> sb.append(escape);
            }
        }
        return sb.toString();
    }

    private Object readNumber() {
        int start = pos;
        while (pos < src.length() && "-+.eE0123456789".indexOf(src.charAt(pos)) >= 0) {
            pos++;
        }
        try {
            return Double.parseDouble(src.substring(start, pos));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Object readLiteral(String literal, Object value) {
        if (src.startsWith(literal, pos)) {
            pos += literal.length();
            return value;
        }
        pos++;
        return null;
    }

    private char peek() {
        return pos < src.length() ? src.charAt(pos) : '\0';
    }

    private void skipWhitespace() {
        while (pos < src.length() && Character.isWhitespace(src.charAt(pos))) {
            pos++;
        }
    }
}
