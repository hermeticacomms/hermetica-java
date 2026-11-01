package com.hermeticacomms.hermetica;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Standard library only. {@code new Client("hm_sk_...", "https://api.example.com")}. Thread-safe. */
public class Client extends Operations {
    private static final Pattern PATH_PARAM = Pattern.compile("\\{(\\w+)\\}");
    private final String apiKey;
    private final String baseUrl;
    private final String workspaceId;
    private final Duration timeout;
    private final HttpClient http;
    private final int maxRetries;

    /** @param apiKey API key, sent as a Bearer token */
    public Client(String apiKey) {
        this(apiKey, "http://localhost:4000");
    }

    /** @param apiKey API key @param baseUrl API origin, e.g. https://api.example.com */
    public Client(String apiKey, String baseUrl) {
        this(apiKey, baseUrl, null, Duration.ofSeconds(30), null);
    }

    /**
     * @param apiKey API key, sent as a Bearer token
     * @param baseUrl API origin, e.g. https://api.example.com
     * @param workspaceId sent as X-Workspace-Id when not null
     * @param timeout per-request timeout
     * @param http the HttpClient to use, or null for a default one
     */
    public Client(String apiKey, String baseUrl, String workspaceId, Duration timeout, HttpClient http) {
        this(apiKey, baseUrl, workspaceId, timeout, http, 3);
    }

    /**
     * @param apiKey API key, sent as a Bearer token
     * @param baseUrl API origin, e.g. https://api.example.com
     * @param workspaceId sent as X-Workspace-Id when not null
     * @param timeout per-request timeout
     * @param http the HttpClient to use, or null for a default one
     * @param maxRetries how many times a network error, 429 or 5xx is retried after the first attempt (default 3; 0 turns retries off)
     */
    public Client(String apiKey, String baseUrl, String workspaceId, Duration timeout, HttpClient http, int maxRetries) {
        this.maxRetries = maxRetries;
        this.apiKey = apiKey;
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.workspaceId = workspaceId;
        this.timeout = timeout;
        this.http = http != null ? http : HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    @Override
    public Object request(String method, String path, Params params) {
        Params p = params == null ? new Params() : params;
        Matcher m = PATH_PARAM.matcher(path);
        StringBuilder resolved = new StringBuilder();
        while (m.find()) {
            if (!p.path.containsKey(m.group(1))) throw new IllegalArgumentException("missing path param " + m.group(1));
            m.appendReplacement(resolved, Matcher.quoteReplacement(encode(String.valueOf(p.path.get(m.group(1)))).replace("+", "%20")));
        }
        m.appendTail(resolved);
        StringJoiner q = new StringJoiner("&");
        for (Map.Entry<String, Object> e : p.query.entrySet()) {
            Object v = e.getValue();
            Iterable<?> values = v instanceof Iterable ? (Iterable<?>) v : Collections.singletonList(v);
            for (Object x : values) if (x != null) q.add(encode(e.getKey()) + "=" + encode(String.valueOf(x)));
        }
        String url = baseUrl + resolved + (q.length() > 0 ? "?" + q : "");
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
            .header("Authorization", "Bearer " + apiKey)
            .header("Accept", "application/json");
        if (workspaceId != null && !workspaceId.isEmpty()) req.header("X-Workspace-Id", workspaceId);
        HttpRequest.BodyPublisher body = HttpRequest.BodyPublishers.noBody();
        if (p.body != null) {
            body = HttpRequest.BodyPublishers.ofString(Json.stringify(p.body));
            req.header("Content-Type", "application/json");
        }
        if (!"GET".equals(method)) {
            String key = p.idempotencyKey == null || p.idempotencyKey.isEmpty() ? UUID.randomUUID().toString() : p.idempotencyKey;
            req.header("Idempotency-Key", key); // a retry with the same key never repeats the action
        }
        HttpRequest built = req.method(method, body).build();
        // Every call is safe to replay: a GET, or a non-GET carrying the same Idempotency-Key on every attempt.
        for (int attempt = 0; ; attempt++) {
            HttpResponse<String> res;
            try {
                res = http.send(built, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            } catch (IOException e) {
                if (attempt >= maxRetries) throw new UncheckedIOException(e);
                pause(backoffMillis(attempt));
                continue;
            } catch (InterruptedException e) {
                throw interrupted();
            }
            int status = res.statusCode();
            if ((status == 429 || status >= 500) && attempt < maxRetries) {
                Long wait = retryAfterMillis(res.headers().firstValue("Retry-After").orElse(null), System.currentTimeMillis());
                pause(wait != null ? wait : backoffMillis(attempt));
                continue;
            }
            String text = res.body() == null ? "" : res.body();
            if (status >= 300) throw errorFor(status, text);
            return text.isEmpty() ? null : Json.parse(text);
        }
    }

    /** Full jitter: a random wait up to 250 ms * 2^attempt, capped at 8 s. */
    static long backoffMillis(int attempt) {
        return ThreadLocalRandom.current().nextLong(Math.min(8000L, 250L << Math.min(attempt, 10)));
    }

    /** Retry-After as delta-seconds or an HTTP date, in ms; null when absent or unparseable. */
    static Long retryAfterMillis(String value, long nowMillis) {
        if (value == null || value.trim().isEmpty()) return null;
        String v = value.trim();
        if (v.matches("-?[0-9]+(\\.[0-9]+)?")) return Math.max(0L, (long) (Double.parseDouble(v) * 1000));
        try {
            return Math.max(0L, ZonedDateTime.parse(v, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() - nowMillis);
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            throw interrupted();
        }
    }

    private static UncheckedIOException interrupted() {
        Thread.currentThread().interrupt();
        return new UncheckedIOException(new java.io.InterruptedIOException("request interrupted"));
    }

    /**
     * Calls {@code fn} for every item of a list operation, following nextCursor.
     *
     * @param list a generated list method, e.g. {@code hm::getV1Profiles}
     * @param params the first page's params (not modified); may be null
     * @param fn called with each entry of items
     */
    public void paginate(Function<Params, Object> list, Params params, Consumer<Object> fn) {
        Params p = params == null ? new Params() : params.copy();
        while (true) {
            Object out = list.apply(p);
            Map<?, ?> page = out instanceof Map ? (Map<?, ?>) out : Collections.emptyMap();
            Object items = page.get("items");
            if (items instanceof List) for (Object item : (List<?>) items) fn.accept(item);
            Object next = page.get("nextCursor");
            if (!(next instanceof String) || ((String) next).isEmpty()) return;
            p.query.put("cursor", next);
        }
    }

    private static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static HermeticaException errorFor(int status, String text) {
        Object out = null;
        if (text.startsWith("{")) {
            try {
                out = Json.parse(text);
            } catch (IllegalArgumentException ignored) {
                // not JSON after all: fall back to the raw text
            }
        }
        Object err = out instanceof Map ? ((Map<?, ?>) out).get("error") : null;
        Map<?, ?> e = err instanceof Map ? (Map<?, ?>) err : Collections.emptyMap();
        Object code = e.get("code");
        Object message = e.get("message");
        return new HermeticaException(status, code instanceof String ? (String) code : "http_error",
            message instanceof String ? (String) message : text.substring(0, Math.min(200, text.length())), e.get("details"));
    }
}
