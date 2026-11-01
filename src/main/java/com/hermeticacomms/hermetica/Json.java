package com.hermeticacomms.hermetica;

import java.lang.reflect.Array;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal JSON codec (the JDK has none). Objects decode to LinkedHashMap, arrays to ArrayList, integers to Long (BigInteger when
 * too large), other numbers to Double. Encodes null, Boolean, Number, CharSequence, Character, Enum, Map, Iterable and arrays.
 */
public final class Json {
    private static final Pattern NUMBER = Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    private static final int MAX_DEPTH = 512;

    private Json() {
    }

    /** @param value the value to encode @return its JSON text */
    public static String stringify(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value);
        return sb.toString();
    }

    /** @param text JSON text @return the decoded value @throws IllegalArgumentException on malformed input */
    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != text.length()) throw p.error("trailing data");
        return v;
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) {
            sb.append("null");
        } else if (v instanceof CharSequence || v instanceof Character || v instanceof Enum) {
            string(sb, v.toString());
        } else if (v instanceof Boolean) {
            sb.append(v);
        } else if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("JSON cannot encode " + d);
            sb.append(d);
        } else if (v instanceof Number) {
            sb.append(v);
        } else if (v instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) {
                if (!first) sb.append(',');
                first = false;
                string(sb, String.valueOf(e.getKey()));
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object x : (Iterable<?>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else if (v.getClass().isArray()) {
            sb.append('[');
            for (int i = 0, n = Array.getLength(v); i < n; i++) {
                if (i > 0) sb.append(',');
                write(sb, Array.get(v, i));
            }
            sb.append(']');
        } else {
            throw new IllegalArgumentException("JSON cannot encode " + v.getClass().getName());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
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

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String what) {
            return new IllegalArgumentException("invalid JSON: " + what + " at " + i);
        }

        void ws() {
            while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++;
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) throw error("nested too deeply");
            if (i >= s.length()) throw error("unexpected end");
            char c = s.charAt(i);
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object(int depth) {
            Map<String, Object> out = new LinkedHashMap<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return out; }
            while (true) {
                ws();
                if (i >= s.length() || s.charAt(i) != '"') throw error("expected a key");
                String key = string();
                ws();
                expect(':');
                ws();
                out.put(key, value(depth + 1));
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return out;
            }
        }

        List<Object> array(int depth) {
            List<Object> out = new ArrayList<>();
            i++;
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return out; }
            while (true) {
                ws();
                out.add(value(depth + 1));
                ws();
                if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return out;
            }
        }

        String string() {
            StringBuilder sb = new StringBuilder();
            i++;
            while (true) {
                if (i >= s.length()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw error("control character in string");
                if (c != '\\') { sb.append(c); continue; }
                if (i >= s.length()) throw error("unterminated escape");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': case '\\': case '/': sb.append(e); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw error("short unicode escape");
                        try {
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw error("bad unicode escape");
                        }
                        i += 4;
                        break;
                    default: throw error("bad escape");
                }
            }
        }

        Object number() {
            int start = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
            String n = s.substring(start, i);
            if (n.isEmpty() || !NUMBER.matcher(n).matches()) { i = start; throw error("unexpected character"); }
            if (n.indexOf('.') < 0 && n.indexOf('e') < 0 && n.indexOf('E') < 0) {
                try {
                    return Long.parseLong(n);
                } catch (NumberFormatException tooBig) {
                    return new BigInteger(n);
                }
            }
            return Double.parseDouble(n);
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw error("expected '" + c + "'");
            i++;
        }
    }
}
