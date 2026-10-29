package com.github.baek.footballobsbackend.client;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import static com.github.baek.footballobsbackend.config.RateLimitTestSettings.limiter;
import static org.assertj.core.api.Assertions.*;

class ApiFootballClientTimeoutTest {
    @Test
    void stalledResponseTimesOutInsteadOfHoldingCacheLoaderForever() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/fixtures", exchange -> {
            try {
                release.await(15, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            var client = new ApiFootballClient("http://127.0.0.1:" + server.getAddress().getPort(),
                    "", limiter("client"));
            long started = System.nanoTime();
            assertThatThrownBy(() -> client.getFixture(1L)).hasRootCauseInstanceOf(SocketTimeoutException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(14));
        } finally {
            release.countDown();
            server.stop(0);
        }
    }
}
