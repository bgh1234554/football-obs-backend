package com.github.baek.footballobsbackend.config;

import java.time.Duration;
import java.time.Instant;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.error.ErrorResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * API 입구에서 서버 자원을 보호하는 IP별 제한.
 *
 * WebConfig가 /api/**에 등록하므로 컨트롤러·서비스·캐시 조회보다 먼저 실행된다.
 * 캐시 적중에도 JSON 복사·가공·직렬화 비용이 있으므로 이 단계는 모든 API 요청을 센다.
 * 외부 호출 횟수는 여기서 판단하지 않고 UpstreamRateLimiter가 별도로 관리한다.
 *
 * 운영 기본값과 환경변수 연결은 application.yaml에만 둔다.
 * 현재 120건·매초 2건 회복은 정상적인 패널 전환에 여유를 주기 위한 초기값이며,
 * 서버 부하 측정으로 확정한 처리 용량이나 업계 표준 수치는 아니다.
 */
@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {
    private final ClientIpResolver clientIpResolver;
    // capacity는 순간 요청 묶음을 허용할 최대 토큰 수다.
    // refillTokens / refillPeriod는 장시간 지속할 수 있는 평균 요청 속도를 정한다.
    // 예: capacity=120, refillTokens=2, refillPeriod=1초이면 처음 120건을 연속 허용하고
    // 이후 매초 2건 분량을 회복한다. 매초 버킷 전체를 다시 채우는 의미가 아니다.
    private final long capacity;
    private final long refillTokens;
    private final Duration refillPeriod;
    // 버킷은 IP마다 다르지만, 더 이상 접속하지 않는 IP까지 영구 보관할 필요는 없다.
    // 10분간 미사용 시 제거하고 최대 10,000개로 메모리 증가를 제한한다.
    // 제거 후 다시 온 IP는 새 버킷을 받으며, 외부 호출의 전체 예산은 별도 버킷으로 보호한다.
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10)).maximumSize(10_000).build();
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    /**
     * 설정은 Spring이 주입하며 누락·잘못된 값은 시작 시 드러나게 한다.
     * Java의 @Value에 기본값을 중복하지 않아 YAML과 서로 다른 값을 갖지 않게 한다.
     *
     * @param capacity 순간 허용량
     * @param refillTokens 한 회복 기간에 충전할 토큰 수
     * @param refillPeriodMs 회복 기간(밀리초)
     */
    @Autowired
    public RateLimitInterceptor(@Value("${rate-limit.server.capacity}") long capacity,
                                @Value("${rate-limit.server.refill-tokens}") long refillTokens,
                                @Value("${rate-limit.server.refill-period-ms}") long refillPeriodMs,
                                ClientIpResolver clientIpResolver) {
        if (capacity <= 0 || refillTokens <= 0 || refillPeriodMs <= 0) throw new IllegalArgumentException("Rate limit values must be positive");
        this.capacity = capacity;
        this.refillTokens = refillTokens;
        this.refillPeriod = Duration.ofMillis(refillPeriodMs);
        this.clientIpResolver = clientIpResolver;
    }

    /** Spring을 사용하지 않는 테스트에서는 전달 헤더를 신뢰하지 않는다. */
    public RateLimitInterceptor(long capacity, long refillTokens, long refillPeriodMs) {
        this(capacity, refillTokens, refillPeriodMs, new ClientIpResolver(false));
    }

    /** Bucket4j가 토큰 회복과 같은 IP의 동시 소비를 안전하게 계산한다. */
    private Bucket createBucket() {
        return Bucket.builder().addLimit(Bandwidth.builder().capacity(capacity)
                .refillGreedy(refillTokens, refillPeriod).build()).build();
    }

    /**
     * 요청당 1건을 소비해 통과시키거나, 컨트롤러 진입 전에 429로 종료한다.
     * true면 이후 캐시 적중 여부와 관계없이 서버 한도 1건은 이미 사용한 것이다.
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String ip = clientIpResolver.resolve(request);
        // get()은 처음 보는 IP의 버킷 생성을 합치고 이후 요청에서는 같은 버킷을 돌려준다.
        // probe에는 승인 여부·잔여량·다음 토큰까지의 대기시간이 함께 들어 있다.
        var probe = buckets.get(ip, key -> createBucket()).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            // 정상 응답에도 입구 버킷 상태를 남겨 어떤 한도를 보고 있는지 구분한다.
            RateLimitHeaders.write(response, "server", capacity, probe.getRemainingTokens(), null);
            return true;
        }
        // 나노초를 밀리초로 내림하면 토큰이 차기 전에 재시도할 수 있어 올림한다.
        long waitMillis = Math.max(1L, (probe.getNanosToWaitForRefill() - 1L) / 1_000_000L + 1L);
        RateLimitHeaders.write(response, "server", capacity, probe.getRemainingTokens(), waitMillis);
        // 인터셉터는 컨트롤러 실행 전에 거절하므로 응답을 직접 작성한다.
        // 외부 호출 제한처럼 ControllerAdvice에 예외 처리를 맡기는 경로와 다르다.
        ErrorCode code = ErrorCode.RATE_LIMIT_EXCEEDED;
        response.setStatus(code.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(new ErrorResult(
                code.name(), code.getMessage(), code.getStatus().value(), request.getRequestURI(), Instant.now())));
        log.info("[LIMIT_EXCEEDED] scope=server ip={} retryAfterMs={}", ip, waitMillis);
        return false;
    }
}
