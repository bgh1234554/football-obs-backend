package com.github.baek.footballobsbackend.config;

import com.github.baek.footballobsbackend.error.UpstreamRateLimitException;
import io.github.bucket4j.TimeMeter;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import static org.assertj.core.api.Assertions.*;
import static com.github.baek.footballobsbackend.config.RateLimitTestSettings.*;

class UpstreamRateLimiterTest {
    private final AtomicLong nanos = new AtomicLong();
    private final TimeMeter time = new TimeMeter() {
        public long currentTimeNanos() { return nanos.get(); }
        public boolean isWallClockBased() { return false; }
    };

    @Test
    void preservesConfiguredBurstAndRefillPeriod() {
        var settings = policy("equal");
        var limiter = settings.create(time);
        for (int i=0;i<settings.globalCapacity();i++) limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(UpstreamRateLimitException.class);
        nanos.set(TimeUnit.MILLISECONDS.toNanos(settings.globalRefillMs()-1));
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(UpstreamRateLimitException.class);
        nanos.set(TimeUnit.MILLISECONDS.toNanos(settings.globalRefillMs()));
        limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(UpstreamRateLimitException.class);
    }

    @Test
    void personalDenialDoesNotSpendGlobalBudget() {
        var settings = policy("personal-first");
        var limiter = settings.create(time);
        limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOfSatisfying(UpstreamRateLimitException.class,
                e -> { assertThat(e.getScope()).isEqualTo("upstream-ip"); assertThat(e.getRetryMillis()).isEqualTo(settings.ipRefillMs()); });
        limiter.acquire("b");
        assertThatThrownBy(() -> limiter.acquire("c")).isInstanceOfSatisfying(UpstreamRateLimitException.class,
                e -> assertThat(e.getScope()).isEqualTo("upstream-global"));
    }

    @Test
    void globalDenialDoesNotSpendPersonalBudget() {
        var settings = policy("global-first");
        var limiter = settings.create(time);
        limiter.acquire("a");
        assertThatThrownBy(() -> limiter.acquire("b")).isInstanceOf(UpstreamRateLimitException.class);
        nanos.set(TimeUnit.MILLISECONDS.toNanos(settings.globalRefillMs()));
        limiter.acquire("b");
    }

    @Test
    void concurrentDifferentClientsCannotOverspendGlobalBudget() throws Exception {
        var settings = policy("concurrent");
        var limiter = settings.create(time);
        var tasks = new ArrayList<Callable<Boolean>>();
        for (int i=0;i<settings.globalCapacity()*4;i++) {
            String ip = "client-"+i;
            tasks.add(() -> { try { limiter.acquire(ip); return true; } catch (UpstreamRateLimitException e) { return false; } });
        }
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            int accepted = 0;
            for (var result : pool.invokeAll(tasks)) if (result.get()) accepted++;
            assertThat(accepted).isEqualTo((int)settings.globalCapacity());
        }
    }

    @Test
    void usesSameVerifiedRemoteIpAsEntryLimiter() {
        var limiter = policy("identity").create(time);
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("203.0.113.7");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try { limiter.acquire(); } finally { RequestContextHolder.resetRequestAttributes(); }
        assertThatThrownBy(() -> limiter.acquire("203.0.113.7")).isInstanceOf(UpstreamRateLimitException.class);
    }
    @Test
    void logsFivePercentBandsOnceAndRearmsOnlyAfterRecovery() {
        Logger logger = (Logger) LoggerFactory.getLogger(UpstreamRateLimiter.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            var settings = policy("usage-bands");
            var limiter = settings.create(time);
            int firstWarning = (int)Math.ceil(settings.globalCapacity()*.8);
            for (int i=0;i<firstWarning-1;i++) limiter.acquire("a");
            assertThat(events.list).isEmpty();
            for (int i=firstWarning-1;i<settings.globalCapacity();i++) limiter.acquire("a");
            assertThat(events.list).extracting(ILoggingEvent::getLevel)
                    .containsExactly(Level.WARN,Level.WARN,Level.WARN,Level.WARN,Level.ERROR);
            for (int band : new int[]{80,85,90,95,100})
                assertThat(events.list).anySatisfy(e -> assertThat(e.getFormattedMessage()).contains("thresholdPercent="+band+" "));
            assertThat(events.list.get(4).getFormattedMessage()).contains("capacity="+settings.globalCapacity()+" remaining=0");
            assertThatThrownBy(() -> limiter.acquire("a")).isInstanceOf(UpstreamRateLimitException.class);
            long nearBoundaryTokens = settings.globalCapacity()/5+1;
            nanos.set(TimeUnit.MILLISECONDS.toNanos(nearBoundaryTokens*settings.globalRefillMs()));
            limiter.acquire("a"); // 80% 부근 회복·재진입은 반복 로그를 만들지 않는다.
            assertThat(events.list).hasSize(5);
            nanos.set(TimeUnit.MILLISECONDS.toNanos((nearBoundaryTokens+settings.globalCapacity())*settings.globalRefillMs()));
            for (int i=0;i<firstWarning;i++) limiter.acquire("a");
            assertThat(events.list).hasSize(6);
            assertThat(events.list.get(5).getFormattedMessage()).contains("thresholdPercent=80 ");
        } finally { logger.detachAppender(events); events.stop(); }
    }

    @Test
    void smallCapacityLogsActualReachedBandWithoutInventingIntermediateEvents() {
        Logger logger = (Logger) LoggerFactory.getLogger(UpstreamRateLimiter.class);
        var events = new ListAppender<ILoggingEvent>();
        events.start(); logger.addAppender(events);
        try {
            var settings = policy("small-bands");
            var limiter = settings.create(time);
            for (int i=0;i<settings.globalCapacity();i++) limiter.acquire("a");
            assertThat(events.list).hasSize(4);
            assertThat(events.list.get(0).getFormattedMessage()).contains("thresholdPercent=80 ");
            assertThat(events.list.get(1).getFormattedMessage()).contains("thresholdPercent=85 ","usedPercent="+Math.round((settings.globalCapacity()-2)*1000.0/settings.globalCapacity())/10.0);
            assertThat(events.list.get(2).getFormattedMessage()).contains("thresholdPercent=90 ");
            assertThat(events.list.get(3).getLevel()).isEqualTo(Level.ERROR);
        } finally { logger.detachAppender(events); events.stop(); }
    }

}
