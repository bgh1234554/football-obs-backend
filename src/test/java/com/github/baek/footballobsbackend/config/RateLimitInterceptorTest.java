package com.github.baek.footballobsbackend.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;
import static com.github.baek.footballobsbackend.config.RateLimitTestSettings.*;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;

class RateLimitInterceptorTest {
    @Test
    void acceptsRequestsWithinYamlConfiguredBurst() throws Exception {
        var settings = server("normal");
        var limiter = settings.create();
        var request = new MockHttpServletRequest("GET","/api/fixtures/last");
        for (int i=0;i<settings.capacity();i++) assertThat(limiter.preHandle(request,new MockHttpServletResponse(),new Object())).isTrue();
    }

    @Test
    void stillBlocksFloodsAndKeepsExistingRetryContract() throws Exception {
        var settings = server("exhausted");
        var limiter = settings.create();
        var request = new MockHttpServletRequest("GET","/api/fixtures/last");
        for (int i=0;i<settings.capacity();i++) assertThat(limiter.preHandle(request,new MockHttpServletResponse(),new Object())).isTrue();
        var response = new MockHttpServletResponse();
        assertThat(limiter.preHandle(request,response,new Object())).isFalse();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getHeader("X-RateLimit-Scope")).isEqualTo("server");
        assertThat(response.getHeader("Cache-Control")).isEqualTo("no-store");
        assertThat(Long.parseLong(response.getHeader("X-Retry-After-Millis"))).isPositive();
        assertThat(response.getContentAsString()).contains("RATE_LIMIT_EXCEEDED");
        request.setRemoteAddr("192.0.2.2");
        assertThat(limiter.preHandle(request,new MockHttpServletResponse(),new Object())).isTrue();
    }
    @Test
    void productionBeansReceiveSettingsFromApplicationYaml() {
        new ApplicationContextRunner().withUserConfiguration(RateLimitInterceptor.class,UpstreamRateLimiter.class,ClientIpResolver.class)
                .withPropertyValues(
                        "RENDER=true",
                        "SERVER_RATE_LIMIT_CAPACITY=" + server("normal").capacity(),
                        "SERVER_RATE_LIMIT_REFILL_TOKENS=" + server("normal").refillTokens(),
                        "SERVER_RATE_LIMIT_REFILL_PERIOD_MS=" + server("normal").refillMs(),
                        "UPSTREAM_IP_RATE_LIMIT_CAPACITY=" + policy("identity").ipCapacity(),
                        "UPSTREAM_IP_RATE_LIMIT_REFILL_PERIOD_MS=" + policy("identity").ipRefillMs(),
                        "UPSTREAM_GLOBAL_RATE_LIMIT_CAPACITY=" + policy("identity").globalCapacity(),
                        "UPSTREAM_GLOBAL_RATE_LIMIT_REFILL_PERIOD_MS=" + policy("identity").globalRefillMs())
                .withInitializer(context -> {
                    try {
                        for (var source : new YamlPropertySourceLoader().load("application",new ClassPathResource("application.yaml")))
                            context.getEnvironment().getPropertySources().addLast(source);
                    } catch (java.io.IOException e) { throw new IllegalStateException(e); }
                }).run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(RateLimitInterceptor.class).hasSingleBean(UpstreamRateLimiter.class);
                    var request = new MockHttpServletRequest();
                    request.setRemoteAddr("::1");
                    request.addHeader("CF-Connecting-IP", "203.0.113.7");
                    request.addHeader("X-Forwarded-For", "198.51.100.99");
                    assertThat(context.getBean(ClientIpResolver.class).resolve(request)).isEqualTo("203.0.113.7");
                    assertThat(context.getEnvironment().getProperty("server.forward-headers-strategy")).isEqualTo("none");
                });
    }

    @Test
    void missingSettingsCannotSilentlyUseJavaDefaults() {
        new ApplicationContextRunner().withUserConfiguration(RateLimitInterceptor.class,UpstreamRateLimiter.class,ClientIpResolver.class)
                .run(context -> assertThat(context).hasFailed());
    }

}
