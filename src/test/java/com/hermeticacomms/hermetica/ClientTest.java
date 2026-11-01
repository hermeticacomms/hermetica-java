package com.hermeticacomms.hermetica;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ClientTest {
    /** One request as the stub server saw it. */
    static final class Seen {
        final String method;
        final String path;
        final String query;
        final Headers headers;
        final String body;

        Seen(String method, String path, String query, Headers headers, String body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }
    }

    private HttpServer server;
    private final List<Seen> seen = Collections.synchronizedList(new ArrayList<>());
    private final Deque<Object[]> answers = new ArrayDeque<>();
    private String base;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(new Seen(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(), ex.getRequestHeaders(), body));
            Object[] answer;
            synchronized (answers) {
                answer = answers.poll();
            }
            byte[] out = ((String) answer[1]).getBytes(StandardCharsets.UTF_8);
            if (answer.length > 2) ex.getResponseHeaders().add("Retry-After", (String) answer[2]);
            ex.sendResponseHeaders((Integer) answer[0], out.length == 0 ? -1 : out.length);
            if (out.length > 0) {
                try (OutputStream os = ex.getResponseBody()) {
                    os.write(out);
                }
            }
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private void answer(int status, String body) {
        answers.add(new Object[] {status, body});
    }

    private void answer(int status, String body, String retryAfter) {
        answers.add(new Object[] {status, body, retryAfter});
    }

    private Client client(String workspaceId) {
        return client(workspaceId, 3);
    }

    private Client client(String workspaceId, int maxRetries) {
        HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();
        return new Client("hm_sk_test", base, workspaceId, Duration.ofSeconds(10), http, maxRetries);
    }

    @Test
    void getSendsAuthQueryAndNoIdempotencyKey() {
        answer(200, "{\"items\":[]}");
        Object out = client("ws_1").request("GET", "/v1/things",
            new Params().query("limit", 5).query("skip", null).query("tag", Arrays.asList("a", "b")));
        assertEquals(Map.of("items", List.of()), out);
        Seen s = seen.get(0);
        assertEquals("GET", s.method);
        assertEquals("/v1/things", s.path);
        assertEquals("limit=5&tag=a&tag=b", s.query);
        assertEquals("Bearer hm_sk_test", s.headers.getFirst("Authorization"));
        assertEquals("ws_1", s.headers.getFirst("X-Workspace-Id"));
        assertNull(s.headers.getFirst("Idempotency-Key"));
    }

    @Test
    void postSendsJsonBodyAndIdempotencyKey() {
        answer(201, "");
        answer(201, "");
        Client c = client(null);
        assertNull(c.request("POST", "/v1/things/{id}", new Params().path("id", "a b/c").body(Map.of("x", 1))));
        c.request("POST", "/v1/things/{id}", new Params().path("id", "1").body(Map.of()).idempotencyKey("key-1"));
        Seen first = seen.get(0);
        assertEquals("/v1/things/a%20b%2Fc", first.path);
        assertEquals("{\"x\":1}", first.body);
        assertEquals("application/json", first.headers.getFirst("Content-Type"));
        assertNull(first.headers.getFirst("X-Workspace-Id"));
        assertTrue(first.headers.getFirst("Idempotency-Key").matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"));
        assertEquals("key-1", seen.get(1).headers.getFirst("Idempotency-Key"));
        assertEquals("{}", seen.get(1).body);
    }

    @Test
    void missingPathParamThrows() {
        assertThrows(IllegalArgumentException.class, () -> client(null).request("GET", "/v1/things/{id}", null));
    }

    @Test
    void errorCarriesStatusCodeMessageDetails() {
        answer(404, "{\"error\":{\"code\":\"not_found\",\"message\":\"No such thing\",\"details\":{\"id\":\"x\"}}}");
        HermeticaException e = assertThrows(HermeticaException.class, () -> client(null).request("GET", "/v1/things", null));
        assertEquals(404, e.status);
        assertEquals("not_found", e.code);
        assertEquals("No such thing", e.errorMessage);
        assertEquals(Map.of("id", "x"), e.details);
        assertEquals("404 not_found: No such thing", e.getMessage());
    }

    @Test
    void nonJsonErrorFallsBackToHttpError() {
        answer(502, "Bad gateway");
        HermeticaException e = assertThrows(HermeticaException.class, () -> client(null, 0).request("DELETE", "/v1/x", null));
        assertEquals(502, e.status);
        assertEquals("http_error", e.code);
        assertEquals("Bad gateway", e.errorMessage);
        assertNull(e.details);
    }

    @Test
    void retriesWithTheSameIdempotencyKey() {
        answer(503, "", "0");
        answer(429, "", "0");
        answer(200, "{\"ok\":true}");
        assertEquals(Map.of("ok", true), client(null).request("POST", "/v1/x", new Params().body(Map.of("a", 1))));
        assertEquals(3, seen.size());
        String key = seen.get(0).headers.getFirst("Idempotency-Key");
        assertTrue(key != null && !key.isEmpty());
        for (Seen s : seen) {
            assertEquals(key, s.headers.getFirst("Idempotency-Key"));
            assertEquals("{\"a\":1}", s.body);
        }
    }

    @Test
    void givesUpAfterMaxRetries() {
        answer(502, "a", "0");
        answer(502, "b", "0");
        HermeticaException e = assertThrows(HermeticaException.class, () -> client(null, 1).request("GET", "/v1/x", null));
        assertEquals(502, e.status);
        assertEquals("b", e.errorMessage);
        assertEquals(2, seen.size());
    }

    @Test
    void doesNotRetry4xx() {
        answer(404, "{}");
        assertThrows(HermeticaException.class, () -> client(null).request("GET", "/v1/x", null));
        assertEquals(1, seen.size());
    }

    @Test
    void networkErrorsAreRetriedThenThrown() {
        // nothing listens on port 1: every attempt fails to connect
        Client c = new Client("hm_sk_test", "http://127.0.0.1:1", null, Duration.ofSeconds(5), null, 1);
        assertThrows(java.io.UncheckedIOException.class, () -> c.request("GET", "/v1/x", null));
    }

    @Test
    void parsesRetryAfter() {
        assertEquals(2000L, Client.retryAfterMillis("2", 0));
        assertEquals(0L, Client.retryAfterMillis("-1", 0));
        assertEquals(5000L, Client.retryAfterMillis("Thu, 1 Jan 1970 00:00:05 GMT", 0));
        assertEquals(0L, Client.retryAfterMillis("Thu, 1 Jan 1970 00:00:05 GMT", 60000));
        assertNull(Client.retryAfterMillis("soon", 0));
        assertNull(Client.retryAfterMillis(null, 0));
        for (int i = 0; i < 20; i++) assertTrue(Client.backoffMillis(i) < 8000L);
    }

    @Test
    void paginateFollowsNextCursor() {
        answer(200, "{\"items\":[1,2],\"nextCursor\":\"c2\"}");
        answer(200, "{\"items\":[3],\"nextCursor\":null}");
        Client c = client(null);
        Params first = new Params().query("limit", 2);
        List<Object> items = new ArrayList<>();
        c.paginate(p -> c.request("GET", "/v1/p", p), first, items::add);
        assertEquals(List.of(1L, 2L, 3L), items);
        assertEquals("limit=2", seen.get(0).query);
        assertEquals("limit=2&cursor=c2", seen.get(1).query);
        assertEquals(Map.of("limit", 2), first.query);
    }

    @Test
    void generatedOperationCallsItsPath() {
        answer(200, "{\"ok\":true}");
        assertEquals(Map.of("ok", true), client(null).getHealthz(null));
        assertEquals("/healthz", seen.get(0).path);
    }
}
