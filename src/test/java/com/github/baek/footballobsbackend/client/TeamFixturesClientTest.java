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
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class TeamFixturesClientTest {
    @Test
    void requestsSixLastAndFiveNextFixturesAndRejectsErrorsInsideHttp200() {
        RestClient.Builder builder = RestClient.builder().baseUrl("https://example.test");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        ApiFootballClient client = new ApiFootballClient("https://example.test", "", limiter("client"));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://example.test/fixtures?team=85&last=6"))
                .andRespond(withSuccess("{\"errors\":[],\"response\":[]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://example.test/fixtures?team=85&next=6"))
                .andRespond(withSuccess("{\"errors\":{\"requests\":\"quota\"},\"response\":[]}", MediaType.APPLICATION_JSON));
        assertThat(client.getTeamFixtures(85, false)).isEmpty();
        assertThatThrownBy(() -> client.getTeamFixtures(85, true)).isInstanceOf(ApiException.class);
        server.verify();
    }
}
