package com.github.baek.footballobsbackend.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.*;

class CdnResponseCacheTest {
    private final AtomicLong nanos = new AtomicLong();
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-24T00:00:00Z"), ZoneOffset.UTC);
    private final CdnResponseCache cache = new CdnResponseCache(clock, nanos::get);
    private final URI uri = URI.create("https://cdn.example.test/fixtures?id=1");
    private final AtomicInteger calls = new AtomicInteger();

    @Test
    void respectsRemainingAgeAndDoesNotExtendTtlOnReads() throws Exception {
        var response = response("public, max-age=15", "[]");
        var headers = new HttpHeaders();
        headers.putAll(response.getHeaders());
        headers.set(HttpHeaders.AGE, "10");
        headers.setVary(List.of("Accept-Encoding"));
        var fetch = fetch(new ResponseEntity<>(response.getBody(), headers, response.getStatusCode()));
        cache.get(uri, fetch);
        nanos.set(TimeUnit.SECONDS.toNanos(4));
        cache.get(uri, fetch);
        assertThat(calls).hasValue(1);
        nanos.set(TimeUnit.SECONDS.toNanos(6));
        cache.get(uri, fetch);
        assertThat(calls).hasValue(2);
    }

    @Test
    void subtractsDateAgeAndResponseDelay() throws Exception {
        var headers = new HttpHeaders();
        headers.setCacheControl("max-age=15");
        headers.setDate(clock.millis() - 12_000);
        var original = response("max-age=15", "[]");
        var fetch = fetch(new ResponseEntity<>(original.getBody(), headers, original.getStatusCode()));
        cache.get(uri, fetch);
        nanos.set(TimeUnit.SECONDS.toNanos(4));
        cache.get(uri, fetch);
        assertThat(calls).hasValue(2);

        var delayed = URI.create("https://cdn.example.test/fixtures?id=2");
        Supplier<ResponseEntity<JsonNode>> slowFetch = () -> {
            nanos.addAndGet(TimeUnit.SECONDS.toNanos(13));
            return fetch.get();
        };
        cache.get(delayed, slowFetch);
        nanos.addAndGet(TimeUnit.SECONDS.toNanos(3));
        cache.get(delayed, slowFetch);
        assertThat(calls).hasValue(4);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "max-age=0", "max-age=10, no-store", "no-cache, max-age=10",
            "private, max-age=10", "max-age=broken", "max-age=10, max-age=20", "s-maxage=60"})
    void doesNotReuseUncacheableResponses(String control) throws Exception {
        var fetch = fetch(response(control, "[]"));
        cache.get(uri, fetch);
        cache.get(uri, fetch);
        assertThat(calls).hasValue(2);
    }

    @Test
    void usesBrowserMaxAgeInsteadOfSharedCacheTtl() throws Exception {
        var fetch = fetch(response("public, max-age=\"15\", s-maxage=300", "[]"));
        cache.get(uri, fetch);
        nanos.set(TimeUnit.SECONDS.toNanos(16));
        cache.get(uri, fetch);
        assertThat(calls).hasValue(2);
    }

    @Test
    void doesNotCacheMissingTtlExpiredAgeOrInvalidAge() throws Exception {
        for (String age : new String[]{"60", "broken", "-1"}) {
            var headers = new HttpHeaders();
            headers.setCacheControl("max-age=60");
            headers.set(HttpHeaders.AGE, age);
            var body = response("max-age=60", "[]").getBody();
            var fetch = fetch(ResponseEntity.ok().headers(headers).body(body));
            cache.get(uri, fetch);
            cache.get(uri, fetch);
        }
        var fetch = fetch(ResponseEntity.ok().body(response("max-age=60", "[]").getBody()));
        cache.get(uri, fetch);
        cache.get(uri, fetch);
        assertThat(calls).hasValue(8);
    }

    @Test
    void separatesKeysAndReturnsIndependentCopies() throws Exception {
        var fetch = fetch(response("max-age=60", "[]"));
        var first = cache.get(uri, fetch);
        ((com.fasterxml.jackson.databind.node.ArrayNode) first.path("response")).add("changed");
        assertThat(cache.get(uri, fetch).path("response")).isEmpty();
        cache.get(URI.create("https://cdn.example.test/fixtures?id=2"), fetch);
        cache.get(URI.create("https://cdn.example.test/players?id=1"), fetch);
        assertThat(calls).hasValue(3);
    }

    @Test
    void doesNotCacheApiErrorsOrExceptions() throws Exception {
        var error = new ObjectMapper().readTree("{\"errors\":{\"requests\":\"quota\"},\"response\":[]}");
        var fetch = fetch(ResponseEntity.ok().header("Cache-Control", "max-age=60").body(error));
        cache.get(uri, fetch);
        cache.get(uri, fetch);
        assertThat(calls).hasValue(2);
        var other = URI.create("https://cdn.example.test/fixtures?id=99");
        assertThatThrownBy(() -> cache.get(other, () -> { throw new IllegalStateException("offline"); }))
                .isInstanceOf(IllegalStateException.class);
        cache.get(other, fetch(response("max-age=60", "[]")));
        assertThat(calls).hasValue(3);
    }

    @Test
    void coalescesConcurrentRequestsForSameKey() throws Exception {
        var response = response("max-age=60", "[]");
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        Supplier<ResponseEntity<JsonNode>> fetch = () -> {
            calls.incrementAndGet();
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("timeout");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            return response;
        };
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool.submit(() -> cache.get(uri, fetch));
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
            var second = pool.submit(() -> cache.get(uri, fetch));
            release.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
            assertThat(calls).hasValue(1);
        } finally {
            release.countDown();
        }
    }

    private ResponseEntity<JsonNode> response(String control, String array) throws Exception {
        JsonNode body = new ObjectMapper().readTree("{\"errors\":[],\"response\":" + array + "}");
        return ResponseEntity.ok().header("Cache-Control", control).body(body);
    }

    private Supplier<ResponseEntity<JsonNode>> fetch(ResponseEntity<JsonNode> response) {
        return () -> {
            calls.incrementAndGet();
            return response;
        };
    }
}
