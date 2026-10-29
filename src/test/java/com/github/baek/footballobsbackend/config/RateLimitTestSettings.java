package com.github.baek.footballobsbackend.config;

import io.github.bucket4j.TimeMeter;
import java.io.IOException;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

/**
 * 테스트 전용 YAML의 합성 정책을 생성자에 주입한다.
 * 운영 application.yaml이나 System 환경변수는 읽지 않아 개발자 환경에 따라 결과가 달라지지 않는다.
 * 운영 YAML의 Spring 주입 경로는 RateLimitInterceptorTest에서 별도로 확인한다.
 */
public final class RateLimitTestSettings {
    private static final PropertySourcesPropertyResolver VALUES = load();
    private RateLimitTestSettings() {}

    private static PropertySourcesPropertyResolver load() {
        var sources = new MutablePropertySources();
        try {
            for (var source : new YamlPropertySourceLoader().load("test-rates",new ClassPathResource("rate-limit-test.yaml")))
                sources.addLast(source);
        } catch (IOException e) { throw new ExceptionInInitializerError(e); }
        return new PropertySourcesPropertyResolver(sources);
    }

    public static long value(String key) { return VALUES.getRequiredProperty("test-rates."+key,Long.class); }

    public record Policy(long ipCapacity,long ipRefillMs,long globalCapacity,long globalRefillMs) {
        public UpstreamRateLimiter create(TimeMeter time) {
            return new UpstreamRateLimiter(ipCapacity,ipRefillMs,globalCapacity,globalRefillMs,time);
        }
        public UpstreamRateLimiter create() { return create(TimeMeter.SYSTEM_NANOTIME); }
    }

    public static Policy policy(String name) {
        String prefix="upstream."+name+".";
        return new Policy(value(prefix+"ip-capacity"),value(prefix+"ip-refill-ms"),
                value(prefix+"global-capacity"),value(prefix+"global-refill-ms"));
    }

    public static UpstreamRateLimiter limiter(String name) { return policy(name).create(); }

    public record ServerPolicy(long capacity,long refillTokens,long refillMs) {
        public RateLimitInterceptor create() { return new RateLimitInterceptor(capacity,refillTokens,refillMs); }
    }

    public static ServerPolicy server(String name) {
        String prefix="server."+name+".";
        return new ServerPolicy(value(prefix+"capacity"),value(prefix+"refill-tokens"),value(prefix+"refill-ms"));
    }
}
