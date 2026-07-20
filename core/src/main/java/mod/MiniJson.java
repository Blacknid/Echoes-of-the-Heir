package mod;

import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

/**
 * A tiny JSON serialiser/parser for the values {@link ModStorage} persists. Handles the Lua value
 * shapes a mod would save: nil, boolean, number, string, and tables used either as arrays
 * (contiguous 1..n integer keys) or as string-keyed objects. Not a general-purpose JSON library —
 * just enough to round-trip mod save data faithfully and safely.
 */
final class MiniJson {

    private MiniJson() {}

    // ── Lua → JSON ──

    static String toJson(LuaValue v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb);
        return sb.toString();
    }

    private static void write(LuaValue v, StringBuilder sb) {
        if (v == null || v.isnil()) { sb.append("null"); return; }
        if (v.isboolean()) { sb.append(v.toboolean()); return; }
        if (v.isint())     { sb.append(v.toint()); return; }
        if (v.isnumber())  { sb.append(v.todouble()); return; }
        if (v.isstring() && !v.istable()) { quote(v.tojstring(), sb); return; }
        if (v.istable())   { writeTable(v.checktable(), sb); return; }
        quote(v.tojstring(), sb);
    }

    private static void writeTable(LuaTable t, StringBuilder sb) {
        int len = t.length();
        boolean asArray = len > 0 && t.keyCount() == len; // contiguous 1..n → array
        if (asArray) {
            sb.append('[');
            for (int i = 1; i <= len; i++) {
                if (i > 1) sb.append(',');
                write(t.get(i), sb);
            }
            sb.append(']');
        } else {
            sb.append('{');
            boolean first = true;
            Varargs k = t.next(LuaValue.NIL);
            while (!k.arg1().isnil()) {
                LuaValue key = k.arg1();
                if (!first) sb.append(',');
                first = false;
                quote(key.tojstring(), sb);
                sb.append(':');
                write(k.arg(2), sb);
                k = t.next(key);
            }
            sb.append('}');
        }
    }

    private static void quote(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default   -> sb.append(c);
            }
        }
        sb.append('"');
    }

    // ── JSON → Lua ──

    static LuaValue fromJson(String json) {
        return new Parser(json).parseValue();
    }

    private static final class Parser {
        private final String s;
        private int i = 0;
        Parser(String s) { this.s = s; }

        LuaValue parseValue() {
            skipWs();
            if (i >= s.length()) return LuaValue.NIL;
            char c = s.charAt(i);
            return switch (c) {
                case '{' -> parseObject();
                case '[' -> parseArray();
                case '"' -> LuaValue.valueOf(parseString());
                case 't', 'f' -> parseBool();
                case 'n' -> parseNull();
                default -> parseNumber();
            };
        }

        private LuaValue parseObject() {
            LuaTable t = new LuaTable();
            i++; // {
            skipWs();
            if (peek() == '}') { i++; return t; }
            while (true) {
                skipWs();
                String key = parseString();
                skipWs();
                expect(':');
                LuaValue val = parseValue();
                t.set(key, val);
                skipWs();
                char c = next();
                if (c == '}') break;
                if (c != ',') throw err("expected , or }");
            }
            return t;
        }

        private LuaValue parseArray() {
            LuaTable t = new LuaTable();
            i++; // [
            skipWs();
            if (peek() == ']') { i++; return t; }
            int idx = 1;
            while (true) {
                t.set(idx++, parseValue());
                skipWs();
                char c = next();
                if (c == ']') break;
                if (c != ',') throw err("expected , or ]");
            }
            return t;
        }

        private String parseString() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c == '\\' && i < s.length()) {
                    char e = s.charAt(i++);
                    switch (e) {
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        default  -> sb.append(e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw err("unterminated string");
        }

        private LuaValue parseBool() {
            if (s.startsWith("true", i))  { i += 4; return LuaValue.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return LuaValue.FALSE; }
            throw err("invalid literal");
        }

        private LuaValue parseNull() {
            if (s.startsWith("null", i)) { i += 4; return LuaValue.NIL; }
            throw err("invalid literal");
        }

        private LuaValue parseNumber() {
            int start = i;
            while (i < s.length() && "+-.eE0123456789".indexOf(s.charAt(i)) >= 0) i++;
            String num = s.substring(start, i);
            try {
                if (num.contains(".") || num.contains("e") || num.contains("E")) {
                    return LuaValue.valueOf(Double.parseDouble(num));
                }
                return LuaValue.valueOf(Integer.parseInt(num));
            } catch (NumberFormatException nfe) {
                return LuaValue.valueOf(Double.parseDouble(num));
            }
        }

        private void skipWs() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
        private char peek()   { return i < s.length() ? s.charAt(i) : '\0'; }
        private char next()   { return i < s.length() ? s.charAt(i++) : '\0'; }
        private void expect(char c) { if (next() != c) throw err("expected '" + c + "'"); }
        private RuntimeException err(String m) { return new RuntimeException("JSON parse: " + m + " at " + i); }
    }
}
