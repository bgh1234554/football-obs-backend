package com.github.baek.footballobsbackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.github.benmanes.caffeine.cache.Ticker;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Clock;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

final class CdnResponseCache {
    private final Cache<URI, Entry> entries;
    private final Clock clock;
    private final Ticker ticker;

    CdnResponseCache() {
        this(Clock.systemUTC(), Ticker.systemTicker());
    }

    CdnResponseCache(Clock clock, Ticker ticker) {
        this.clock = clock;
        this.ticker = ticker;
        this.entries = Caffeine.newBuilder()
                .maximumWeight(32 * 1024 * 1024)
                .weigher((URI key, Entry value) -> value.weight())
                .ticker(ticker)
                .expireAfter(new Expiry<URI, Entry>() {
                    public long expireAfterCreate(URI key, Entry value, long now) {
                        return value.ttlNanos();
                    }
                    public long expireAfterUpdate(URI key, Entry value, long now, long duration) {
                        return value.ttlNanos();
                    }
                    public long expireAfterRead(URI key, Entry value, long now, long duration) {
                        return duration;
                    }
                })
                .build();
    }

    // 같은 URI의 동시 조회를 합치고, 호출자가 캐시 원본을 바꾸지 못하게 복사본을 반환한다.
    JsonNode get(URI uri, Supplier<ResponseEntity<JsonNode>> fetch) {
        return get(uri, fetch, false);
    }

    JsonNode get(URI uri, Supplier<ResponseEntity<JsonNode>> fetch, boolean refresh) {
        java.util.function.Function<URI, Entry> load = key -> {
            long started = ticker.read();
            ResponseEntity<JsonNode> response = fetch.get();
            JsonNode body = response.getBody();
            int weight = body == null ? 1 : (int) Math.min(Integer.MAX_VALUE,
                    256L + body.toString().length() * 2L);
            long ttl = remainingTtl(response, ticker.read() - started);
            if (uri.getPath().endsWith("/players/profiles") && uri.getRawQuery() != null
                    && uri.getRawQuery().contains("page=") && (body == null || !body.has("paging"))) ttl = 0;
            return new Entry(body, ttl, weight);
        };
        // 일반 get은 캐시 적중이면 load를 실행하지 않고, 미적중인 같은 URI의 동시 조회는 합친다.
        // 외부 제한 승인은 load 안의 fetch에서만 수행되므로 기다리는 요청마다 차감하지 않는다.
        // 수동 새로고침은 읽기를 건너뛰고 같은 키의 캐시를 원자적으로 갱신한다.
        // 명시적인 refresh 호출들은 순서대로 각자 새로 조회한다. 일반 get의 동시 병합과 다르다.
        // load가 제한 예외를 던지면 새 캐시 값은 기록되지 않고 기존 refresh 대상 항목도 유지된다.
        Entry entry = refresh ? entries.asMap().compute(uri, (key, old) -> load.apply(key))
                : entries.get(uri, load);
        return entry.body() == null ? null : entry.body().deepCopy();
    }

    // 브라우저용 max-age를 따르되 Age와 전송 시간을 빼서 TTL을 다시 시작하지 않는다.
    private long remainingTtl(ResponseEntity<JsonNode> response, long responseDelay) {
        JsonNode body = response.getBody();
        if (response.getStatusCode().value() != 200 || body == null
                || !body.path("response").isArray()
                || (!body.path("errors").isMissingNode() && !body.path("errors").isEmpty())) return 0;
        if (body.has("paging") && (!body.path("paging").path("current").isIntegralNumber()
                || !body.path("paging").path("total").isIntegralNumber()
                || body.path("paging").path("current").asInt() < 1
                || body.path("paging").path("total").asInt() < 0)) return 0;
        HttpHeaders headers = response.getHeaders();
        if (headers.containsKey(HttpHeaders.SET_COOKIE)
                || headers.getVary().stream().anyMatch(v -> !v.equalsIgnoreCase(HttpHeaders.ACCEPT_ENCODING))) return 0;
        String control = headers.getCacheControl();
        if (control == null) return 0;
        Long maxAge = null;
        try {
            for (String directive : control.split(",")) {
                String[] pair = directive.strip().split("=", 2);
                String name = pair[0].strip().toLowerCase(Locale.ROOT);
                if (name.equals("no-store") || name.equals("no-cache") || name.equals("private")) return 0;
                if (name.equals("max-age")) {
                    if (maxAge != null || pair.length != 2) return 0;
                    String value = pair[1].strip();
                    if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
                    if (!value.matches("[0-9]+")) return 0;
                    maxAge = Long.parseLong(value);
                }
            }
            if (maxAge == null) return 0;
            String ageHeader = headers.getFirst(HttpHeaders.AGE);
            long age = ageHeader == null ? 0 : Long.parseLong(ageHeader.strip());
            if (age < 0) return 0;
            long date = headers.getDate();
            long apparentAge = date < 0 ? 0 : Math.max(0, clock.millis() - date);
            long correctedAge = Math.addExact(Math.multiplyExact(age, 1000),
                    TimeUnit.NANOSECONDS.toMillis(Math.max(0, responseDelay)));
            long remaining = Math.max(0, Math.subtractExact(Math.multiplyExact(maxAge, 1000),
                    Math.max(apparentAge, correctedAge)));
            return TimeUnit.MILLISECONDS.toNanos(remaining);
        } catch (IllegalArgumentException | ArithmeticException invalidHeader) {
            return 0;
        }
    }

    private record Entry(JsonNode body, long ttlNanos, int weight) {}
}
