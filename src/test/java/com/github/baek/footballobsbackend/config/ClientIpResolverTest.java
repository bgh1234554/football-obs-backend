package com.github.baek.footballobsbackend.config;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;

class ClientIpResolverTest {
    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({TrustedProxyConfig.class, IpController.class})
    static class TestApplication {}

    @RestController
    static class IpController {
        @GetMapping("/ip")
        String ip(HttpServletRequest request) { return ClientIpResolver.resolve(request); }
    }

    @Test
    void trustedProxyChainStopsAtRightmostUntrustedAddress() {
        assertThat(resolve("127\\.0\\.0\\.1|10\\.0\\.0\\.1", "198.51.100.99, 203.0.113.7, 10.0.0.1"))
                .isEqualTo("203.0.113.7");
    }

    @Test
    void untrustedConnectionCannotSpoofAddress() {
        assertThat(resolve("(?!)", "198.51.100.99, 203.0.113.7"))
                .isEqualTo("127.0.0.1");
    }

    @Test
    void blankProxyConfigurationIsRejected() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new TrustedProxyConfig().validateTrustedProxies(" "));
    }

    private String resolve(String proxies, String forwarded) {
        try (var context = new SpringApplicationBuilder(TestApplication.class).run(
                "--server.port=0", "--server.address=127.0.0.1", "--spring.main.banner-mode=off",
                "--server.forward-headers-strategy=native",
                "--server.tomcat.remoteip.internal-proxies=" + proxies)) {
            int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
            return RestClient.create().get().uri("http://127.0.0.1:" + port + "/ip")
                    .header("X-Forwarded-For", forwarded).retrieve().body(String.class);
        }
    }
}
