package com.hermeticacomms.hermetica;

import java.util.LinkedHashMap;
import java.util.Map;

/** The inputs of one call: path params, query params, a JSON body and an optional Idempotency-Key. */
public final class Params {
    /** Values for the {name} placeholders of the path. */
    public final Map<String, Object> path = new LinkedHashMap<>();
    /** Query parameters; null values are skipped and an Iterable value repeats the key. */
    public final Map<String, Object> query = new LinkedHashMap<>();
    /** The JSON body (Map, List, String, Number, Boolean, array) or null for none. */
    public Object body;
    /** Idempotency-Key for a non-GET call; a random one is sent when null. */
    public String idempotencyKey;

    /** @param name placeholder name @param value its value @return this */
    public Params path(String name, Object value) { path.put(name, value); return this; }

    /** @param name query parameter @param value its value @return this */
    public Params query(String name, Object value) { query.put(name, value); return this; }

    /** @param body the JSON body @return this */
    public Params body(Object body) { this.body = body; return this; }

    /** @param key the Idempotency-Key to send @return this */
    public Params idempotencyKey(String key) { this.idempotencyKey = key; return this; }

    /** @return a copy whose maps can be changed independently */
    public Params copy() {
        Params p = new Params();
        p.path.putAll(path);
        p.query.putAll(query);
        p.body = body;
        p.idempotencyKey = idempotencyKey;
        return p;
    }
}
