package com.github.baek.footballobsbackend.client;

import com.github.baek.footballobsbackend.error.ApiException;
import org.junit.jupiter.api.Test;
import static com.github.baek.footballobsbackend.config.RateLimitTestSettings.*;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class PlayerSearchClientTest {
    @Test void forwardsPagesAndEncodesNameAsOneQueryValue() {
        var builder = RestClient.builder().baseUrl("https://example.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new ApiFootballClient("https://example.test", "", limiter("client"));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://example.test/players/profiles?search=%20Germ%C3%A1n%20Iva%26page%3D99&page=2"))
                .andRespond(withSuccess("{\"errors\":[],\"response\":[],\"paging\":{\"current\":2,\"total\":3}}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/players/profiles?player=5075&page=1"))
                .andRespond(withSuccess("{\"errors\":[],\"response\":[],\"paging\":{\"current\":1,\"total\":1}}", MediaType.APPLICATION_JSON));
        assertThat(client.searchPlayerProfiles(" Germán Iva&page=99", 2).path("paging").path("current").asInt()).isEqualTo(2);
        assertThat(client.searchPlayerProfiles("5075", 1).path("response")).isEmpty();
        server.verify();
    }
    @Test void rejectsProviderErrorsAndMalformedEnvelopes() {
        for (String body : new String[]{"{\"errors\":{\"requests\":\"quota\"},\"response\":[]}", "{\"response\":[]}", "{\"response\":{},\"paging\":{\"current\":1,\"total\":1}}"}) {
            var builder = RestClient.builder().baseUrl("https://example.test");
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new ApiFootballClient("https://example.test", "", limiter("client"));
            ReflectionTestUtils.setField(client, "restClient", builder.build());
            server.expect(requestTo("https://example.test/players/profiles?search=Smith&page=1")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            assertThatThrownBy(() -> client.searchPlayerProfiles("Smith", 1)).isInstanceOf(ApiException.class);
            server.verify();
        }
    }
    @Test void rejectsUpstreamHttpFailure() {
        var builder = RestClient.builder().baseUrl("https://example.test");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new ApiFootballClient("https://example.test", "", limiter("client"));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://example.test/players/profiles?search=Smith&page=1"))
                .andRespond(withServerError());
        assertThatThrownBy(() -> client.searchPlayerProfiles("Smith", 1)).isInstanceOf(ApiException.class);
        server.verify();
    }
}
