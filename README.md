# Hermetica Java client

Generated from the Hermetica OpenAPI spec (2041 operations). Java 11+, no dependencies (java.net.http and a small built-in JSON codec).

## Install

Maven:

```xml
<dependency>
  <groupId>com.hermeticacomms</groupId>
  <artifactId>hermetica-java</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle: `implementation("com.hermeticacomms:hermetica-java:0.1.0")`

## Quick start

```java
import com.hermeticacomms.hermetica.Client;
import com.hermeticacomms.hermetica.Params;
import java.util.Map;

Client hm = new Client("hm_sk_...", "https://api.example.com");
Object page = hm.getV1Profiles(new Params().query("limit", 25));
hm.postV1Events(new Params()
    .body(Map.of("name", "signed_up", "profile", Map.of("email", "ada@example.com")))
    .idempotencyKey("signup-ada-1"));
```

One method per operationId, camelCase. Each takes a `Params` (`path`, `query`, `body`, `idempotencyKey`; `null` means none) and
returns the parsed JSON: `Map<String, Object>`, `List<Object>`, `String`, `Long`/`BigInteger`/`Double`, `Boolean` or `null`.
Use `new Client(apiKey, baseUrl, workspaceId, timeout, httpClient[, maxRetries])` to send `X-Workspace-Id` or bring your own `HttpClient`.
`Json.parse` / `Json.stringify` are public if you need them.

## Paging

```java
hm.paginate(hm::getV1Profiles, new Params().query("limit", 100), profile -> System.out.println(((Map<?, ?>) profile).get("id")));
```

`paginate` follows `nextCursor` until it is empty and calls you with every entry of `items`.

## Idempotency

Every non-GET call sends an `Idempotency-Key` (a random UUID unless you set `idempotencyKey`).

## Retries

Network errors, 429 and 5xx are retried automatically with exponential backoff and jitter, honouring `Retry-After`. Every attempt of
a call sends the same `Idempotency-Key`, so a retried write never runs twice. `maxRetries` (the sixth constructor argument) defaults
to 3; `0` turns retries off. When you retry a failed call yourself, pass the same `idempotencyKey`.

## Errors

Any non-2xx answer throws `HermeticaException` (unchecked) with `status`, `code`, `errorMessage` and `details`; `getMessage()` is
"status code: errorMessage". Network failures, once retries run out, throw `UncheckedIOException`.

```java
try {
    hm.getV1ProfilesById(new Params().path("id", "missing"));
} catch (HermeticaException e) {
    System.err.println(e.status + " " + e.code + ": " + e.errorMessage);
}
```

Consent and suppression are enforced by the API; the client cannot bypass them.
