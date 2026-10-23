package com.github.baek.footballobsbackend.client;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ApiFootballClientRefererTest {
    @Test
    void sendsConfiguredRefererOnCdnRequests() throws Exception {
        assertReferer("  https://backend.example.test/  ", "https://backend.example.test/");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "   "})
    void omitsRefererWhenNotConfigured(String configured) throws Exception {
        assertReferer(configured, null);
    }

    private void assertReferer(String configured, String expected) throws Exception {
        AtomicReference<String> received = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixtures", exchange -> {
            received.set(exchange.getRequestHeaders().getFirst("Referer"));
            byte[] body = "{\"errors\":[],\"response\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
            ApiFootballClient client = new ApiFootballClient(baseUrl, configured);
            assertThat(client.getTeamFixtures(85, false)).isEmpty();
            assertThat(received.get()).isEqualTo(expected);
        } finally {
            server.stop(0);
        }
    }
}
