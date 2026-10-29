package com.github.baek.footballobsbackend.config;

import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import com.github.baek.footballobsbackend.error.UpstreamRateLimitException;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 캐시로 해결되지 않는 실제 외부 HTTP 시도를 보호한다.
 *
 * ApiFootballClient가 CdnResponseCache의 로더 안에서 호출한다.
 * 따라서 캐시 적중과 같은 URI의 일반 동시 요청에 합쳐진 대기자는 차감하지 않는다.
 * 서버 입구 제한을 통과했더라도 이 단계의 외부 한도는 별도로 부족할 수 있다.
 *
 * 사용자별 버킷은 한 IP의 사용량, global 버킷은 여러 IP의 합계를 제한한다.
 * global은 이 Spring 싱글턴/JVM 안에서 공유되며 여러 서버 간 공용 버킷은 아니다.
 * HTTP 시도를 허용한 뒤에는 실패해도 토큰을 반환하지 않는다.
 */
@Component
@Slf4j
public class UpstreamRateLimiter {
    private final long ipCapacity;
    private final long globalCapacity;
    private final long ipRefillMs;
    // 벽시계 보정의 영향을 받지 않는 시간원으로 토큰 회복을 계산한다.
    // 테스트는 가짜 TimeMeter를 주입해 실제로 4초를 기다리지 않고 경계를 검증한다.
    private final TimeMeter time;
    private final Bucket global;
    // acquire(String)의 잠금 안에서만 접근한다. 같은 경고 구간의 반복 출력을 막는다.
    private int lastGlobalUsageBand = 0;
    // IP별 버킷만 수명·개수 제한이 있는 캐시에 보관한다. global은 제거하지 않는다.
    private final Cache<String, Bucket> clients = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10)).maximumSize(10_000).build();

    /** 운영 생성자: 한도는 YAML에서 주입하고, 시간은 단조 증가하는 시스템 시간을 쓴다. */
    @Autowired
    public UpstreamRateLimiter(@Value("${rate-limit.upstream.ip-capacity}") long ipCapacity,
                               @Value("${rate-limit.upstream.ip-refill-period-ms}") long ipRefillMs,
                               @Value("${rate-limit.upstream.global-capacity}") long globalCapacity,
                               @Value("${rate-limit.upstream.global-refill-period-ms}") long globalRefillMs) {
        this(ipCapacity, ipRefillMs, globalCapacity, globalRefillMs, TimeMeter.SYSTEM_NANOTIME);
    }

    /** 테스트 생성자: 가짜 시간으로 회복 속도와 두 버킷의 동시 승인을 검증한다. */
    UpstreamRateLimiter(long ipCapacity, long ipRefillMs, long globalCapacity, long globalRefillMs, TimeMeter time) {
        if (ipCapacity <= 0 || globalCapacity <= 0 || ipRefillMs <= 0 || globalRefillMs <= 0)
            throw new IllegalArgumentException("Rate limit values must be positive");
        this.ipCapacity = ipCapacity;
        this.globalCapacity = globalCapacity;
        this.ipRefillMs = ipRefillMs;
        this.time = time;
        global = bucket(globalCapacity, globalRefillMs);
    }

    /**
     * 외부 호출 버킷은 refillMs 동안 1건씩 회복한다.
     * 기본값 15/4000은 처음 최대 15건, 이후 평균 4초마다 1건이며 분당 고정 창이 아니다.
     */
    private Bucket bucket(long capacity, long refillMs) {
        return Bucket.builder().withCustomTimePrecision(time).addLimit(Bandwidth.builder()
                .capacity(capacity).refillGreedy(1, Duration.ofMillis(refillMs)).build()).build();
    }

    /**
     * 현재 HTTP 요청의 IP를 찾아 외부 호출 1건의 실행 허가를 요청한다.
     * 서버 입구와 같은 IP 규칙을 써야 서로 다른 사용자를 잘못 묶거나 나누지 않는다.
     * HTTP 요청 밖의 호출도 우회시키지 않고 internal 키와 전체 한도를 적용한다.
     * 현재 서비스 호출은 같은 요청 스레드에서 동기 실행되므로 요청 문맥을 읽을 수 있다.
     * 향후 비동기 작업으로 옮기면 요청자 식별을 명시적으로 전달해야 한다.
     *
     * @throws UpstreamRateLimitException 허용량이 없으면 실제 HTTP 요청 전에 발생
     */
    public void acquire() {
        var attributes = RequestContextHolder.getRequestAttributes();
        String ip = attributes instanceof ServletRequestAttributes servlet
                ? ClientIpResolver.resolve(servlet.getRequest()) : "internal";
        acquire(ip);
    }

    /**
     * 사용자별·전체 버킷을 하나의 승인 절차로 묶는다.
     *
     * 각 Bucket4j 버킷 자체는 스레드 안전하지만 서로 다른 두 버킷의 소비까지
     * 하나로 묶어주지는 않는다. 따라서 조회→승인 판단→양쪽 차감 동안 같은 잠금을 쓴다.
     * 한쪽을 먼저 차감한 뒤 다른 쪽에서 거절하는 부분 소비를 피하기 위한 것이다.
     *
     * 이 잠금은 짧은 버킷 계산에만 걸린다. 캐시 로딩과 네트워크 응답 대기는
     * 이 메서드가 반환한 뒤 실행되므로 느린 외부 요청이 다른 호출의 승인을 막지 않는다.
     */
    synchronized void acquire(String ip) {
        // 조용한 동안 회복된 토큰도 다음 호출에서 관측해 경고 단계를 초기화한다.
        observeGlobalUsage(global.getAvailableTokens());
        Bucket client = clients.get(ip, key -> bucket(ipCapacity, ipRefillMs));
        // estimate는 승인 가능 여부와 대기시간만 계산하고 토큰은 소비하지 않는다.
        var personal = client.estimateAbilityToConsume(1);
        var shared = global.estimateAbilityToConsume(1);
        if (!personal.canBeConsumed() || !shared.canBeConsumed()) {
            // 두 한도가 모두 부족하면 더 오래 기다려야 하는 쪽을 응답 사유로 표시한다.
            // 재시도에는 양쪽 모두 토큰이 필요하므로 대기시간도 큰 값을 선택한다.
            // 이 분기에서는 어떤 버킷의 토큰도 차감하지 않는다.
            boolean globalBlocked = shared.getNanosToWaitForRefill() >= personal.getNanosToWaitForRefill();
            long waitNanos = Math.max(personal.getNanosToWaitForRefill(), shared.getNanosToWaitForRefill());
            throw new UpstreamRateLimitException(globalBlocked ? "upstream-global" : "upstream-ip",
                    globalBlocked ? globalCapacity : ipCapacity, Math.max(1L, (waitNanos - 1L) / 1_000_000L + 1L));
        }
        // 모든 acquire가 같은 잠금을 사용하므로 승인과 이 차감 사이에 다른 호출이 끼지 않는다.
        // 이 시점부터 외부 호출 시도 1건으로 계산한다. 후속 네트워크 실패는 환불하지 않는다.
        client.tryConsume(1);
        var consumed = global.tryConsumeAndReturnRemaining(1);
        observeGlobalUsage(consumed.getRemainingTokens());
    }

    /**
     * 전체 버킷의 80·85·90·95% 소진은 WARN, 완전 소진은 ERROR로 한 번씩 기록한다.
     * 토큰 단위가 커서 여러 구간을 한 번에 넘으면 가장 높은 도달 구간만 기록한다.
     * 회복과 소비가 경계 부근에서 반복돼 로그가 쏟아지지 않도록 60% 이하에서만 재무장한다.
     * 별도 타이머나 HTTP 호출 없이 acquire의 기존 잠금 안에서 현재 토큰만 관측한다.
     */
    private void observeGlobalUsage(long remaining) {
        double usedPercent = (globalCapacity - remaining) * 100.0 / globalCapacity;
        if (usedPercent <= 60) lastGlobalUsageBand = 0;
        int band = remaining == 0 ? 100 : Math.min(95, (int) (usedPercent / 5) * 5);
        if (band < 80 || band <= lastGlobalUsageBand) return;
        lastGlobalUsageBand = band;
        double roundedPercent = Math.round(usedPercent * 10) / 10.0;
        if (remaining == 0) {
            log.error("[UPSTREAM_BUDGET_USAGE] scope=upstream-global thresholdPercent={} usedPercent={} capacity={} remaining={}",
                    band, roundedPercent, globalCapacity, remaining);
        } else {
            log.warn("[UPSTREAM_BUDGET_USAGE] scope=upstream-global thresholdPercent={} usedPercent={} capacity={} remaining={}",
                    band, roundedPercent, globalCapacity, remaining);
        }
    }
}
