package server;

/**
 * A deliberately tiny JSON helper for the engine↔parent protocol.
 *
 * <p>The protocol messages are flat objects of a handful of known keys (see {@link EngineServer}),
 * so this does not need — and does not want — a full JSON library on the server classpath. It
 * reads one field at a time by name and escapes strings on the way out. It is not a general
 * parser: it assumes well-formed input produced by the two ends of this private link, and reads
 * only top-level scalar fields.
 *
 * <p>Keys are matched on the literal {@code "key":} token so a key is never confused with another
 * that merely ends in the same letters — the same substring-collision trap the client's JSON
 * reader has (which is why the wire protocol uses unambiguous camelCase ids like {@code mobId}).
 */
final class Json {

    private Json() {}

    /** Read a string field, or {@code null} if absent. Unescapes {@code \"} and {@code \\}. */
    static String str(String json, String key) {
        int v = valueStart(json, key);
        if (v < 0 || v >= json.length() || json.charAt(v) != '"') return null;
        StringBuilder sb = new StringBuilder();
        int i = v + 1;
        while (i < json.length()) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(i + 1);
                switch (n) {
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case '/' -> sb.append('/');
                    default -> sb.append(n);
                }
                i += 2;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /** Read an integer field, returning {@code fallback} if it is absent or unparseable. */
    static int integer(String json, String key, int fallback) {
        int v = valueStart(json, key);
        if (v < 0 || v >= json.length()) return fallback;
        int digitsStart = v;
        if (json.charAt(v) == '-' || json.charAt(v) == '+') digitsStart++;
        int end = digitsStart;
        while (end < json.length() && Character.isDigit(json.charAt(end))) end++;
        if (end == digitsStart) return fallback;   // no digits after optional sign
        try {
            return Integer.parseInt(json.substring(v, end));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Index of the first character of the value for {@code "key":}, skipping whitespace after the
     * colon, or -1 if the key is not present. Matches the whole {@code "key":} token so
     * {@code "id"} does not match inside {@code "mobId"}.
     */
    private static int valueStart(String json, String key) {
        String token = "\"" + key + "\"";
        int from = 0;
        while (true) {
            int k = json.indexOf(token, from);
            if (k < 0) return -1;
            int i = k + token.length();
            while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
            if (i < json.length() && json.charAt(i) == ':') {
                i++;
                while (i < json.length() && Character.isWhitespace(json.charAt(i))) i++;
                return i;
            }
            from = k + token.length();   // token was part of a longer key/value; keep looking
        }
    }

    /** Escape a string for embedding in a JSON string literal. */
    static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
