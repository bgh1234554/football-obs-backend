package com.github.baek.footballobsbackend.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class ClientIpDiagnosticsTest {
    @Test
    void diagnosticsAreOptInAndDoNotChangeSelectedIp() {
        Logger logger = (Logger) LoggerFactory.getLogger(ClientIpResolver.class);
        Level original = logger.getLevel();
        var events = new ListAppender<ILoggingEvent>();
        events.start();
        logger.addAppender(events);
        try {
            var request = new MockHttpServletRequest("GET", "/api/__ip_check__");
            request.setRemoteAddr("10.0.0.1");
            request.setParameter("case", "spoof\r\nmarker");
            request.addHeader("X-Forwarded-For", "198.51.100.99");
            request.addHeader("X-Forwarded-For", "203.0.113.7");
            request.addHeader("CF-Connecting-IP", "203.0.113.7");
            request.addHeader("True-Client-IP", "x".repeat(600));

            logger.setLevel(Level.INFO);
            assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.1");
            assertThat(events.list).isEmpty();

            logger.setLevel(Level.DEBUG);
            assertThat(ClientIpResolver.resolve(request)).isEqualTo("10.0.0.1");
            assertThat(events.list).hasSize(1);
            assertThat(events.list.getFirst().getFormattedMessage())
                    .contains("[CLIENT_IP_DIAGNOSTIC]", "case=spoof__marker",
                            "remoteAddr=10.0.0.1 selectedIp=10.0.0.1",
                            "xForwardedFor=198.51.100.99,203.0.113.7", "cfConnectingIp=203.0.113.7")
                    .doesNotContain("\r", "\n", "x".repeat(513));

            request.setRequestURI("/api/fixtures/1");
            ClientIpResolver.resolve(request);
            assertThat(events.list).hasSize(1);
        } finally {
            logger.detachAppender(events);
            events.stop();
            logger.setLevel(original);
        }
    }
}
