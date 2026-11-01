package com.hermeticacomms.hermetica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class JsonTest {
    @Test
    void parsesEveryKind() {
        Object v = Json.parse(" {\"s\":\"a\\\"b\\\\c\\/\\n\\u00e9\\ud83d\\ude00\",\"n\":-12,\"big\":123456789012345678901234567890,"
            + "\"d\":1.5e3,\"t\":true,\"f\":false,\"z\":null,\"a\":[1,[],{}]} ");
        Map<?, ?> m = (Map<?, ?>) v;
        assertEquals("a\"b\\c/\n\u00e9\ud83d\ude00", m.get("s"));
        assertEquals(-12L, m.get("n"));
        assertEquals(new BigInteger("123456789012345678901234567890"), m.get("big"));
        assertEquals(1500.0, m.get("d"));
        assertEquals(true, m.get("t"));
        assertEquals(false, m.get("f"));
        assertNull(m.get("z"));
        assertEquals(Arrays.asList(1L, List.of(), Map.of()), m.get("a"));
        assertNull(Json.parse("null"));
        assertEquals("x", Json.parse("\"x\""));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : new String[] {"", "{", "[1,]", "{\"a\":1,}", "01", "-", "tru", "\"abc", "\"\\x\"", "\"\\u12\"", "1 2", "{a:1}", "\"\u0001\""}) {
            assertThrows(IllegalArgumentException.class, () -> Json.parse(bad), bad);
        }
        StringBuilder deep = new StringBuilder();
        for (int i = 0; i < 1000; i++) deep.append('[');
        assertThrows(IllegalArgumentException.class, () -> Json.parse(deep.toString()));
    }

    @Test
    void stringifiesEveryKind() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("s", "q\"\\\n\t\u0001é");
        m.put("n", 42);
        m.put("d", 2.5);
        m.put("b", false);
        m.put("z", null);
        m.put("l", List.of(1, "x"));
        m.put("arr", new int[] {1, 2});
        assertEquals("{\"s\":\"q\\\"\\\\\\n\\t\\u0001é\",\"n\":42,\"d\":2.5,\"b\":false,\"z\":null,\"l\":[1,\"x\"],\"arr\":[1,2]}", Json.stringify(m));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Json.stringify(new Object()));
    }

    @Test
    void roundTrips() {
        String text = "{\"a\":[1,2.5,\"x\",null,true],\"b\":{\"c\":{}}}";
        assertEquals(text, Json.stringify(Json.parse(text)));
    }
}
