package com.github.baek.footballobsbackend.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ApiFootballClientCacheTest {
    @Test
    void reusesCdnResponseAndSeparatesExpandedSearchQueries() {
        var builder = RestClient.builder().baseUrl("https://example.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new ApiFootballClient("https://example.test");
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        String body = "{\"errors\":[],\"response\":[],\"paging\":{\"current\":1,\"total\":1}}";
        server.expect(requestTo("https://example.test/players/profiles?search=Smith&page=1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header("Cache-Control", "max-age=60"));
        server.expect(requestTo("https://example.test/players/profiles?search=Jones&page=1"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON).header("Cache-Control", "max-age=60"));
        assertThat(client.searchPlayerProfiles("Smith", 1).path("response")).isEmpty();
        assertThat(client.searchPlayerProfiles("Smith", 1).path("response")).isEmpty();
        assertThat(client.searchPlayerProfiles("Jones", 1).path("response")).isEmpty();
        server.verify();
    }
    @Test
    void manualRefreshReplacesCachedResponsesForEveryFixtureDependency() {
        var builder = RestClient.builder().baseUrl("https://example.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new ApiFootballClient("https://example.test");
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        String[] paths = {"/fixtures?id=1", "/injuries?ids=1", "/players/squads?team=85", "/players/profiles?player=10"};
        for (String path : paths) {
            for (int version : new int[]{1, 2}) {
                server.expect(requestTo("https://example.test" + path))
                        .andRespond(withSuccess("{\"errors\":[],\"response\":[{\"version\":" + version + "}]}",
                                MediaType.APPLICATION_JSON).header("Cache-Control", "max-age=60"));
            }
        }
        assertRefresh(() -> client.getFixture(1), () -> client.getFixture(1, true), true);
        assertRefresh(() -> client.getInjuries(1), () -> client.getInjuries(1, true), false);
        assertRefresh(() -> client.getPlayerSquad(85), () -> client.getPlayerSquad(85, true), false);
        assertRefresh(() -> client.getPlayerProfile(10), () -> client.getPlayerProfile(10, true), false);
        server.verify();
    }

    private void assertRefresh(java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> normal,
                               java.util.function.Supplier<com.fasterxml.jackson.databind.JsonNode> refresh,
                               boolean single) {
        var first = normal.get();
        assertThat((single ? first : first.get(0)).path("version").asInt()).isEqualTo(1);
        assertThat(normal.get()).isEqualTo(first);
        var updated = refresh.get();
        assertThat((single ? updated : updated.get(0)).path("version").asInt()).isEqualTo(2);
        assertThat(normal.get()).isEqualTo(updated);
    }

}
