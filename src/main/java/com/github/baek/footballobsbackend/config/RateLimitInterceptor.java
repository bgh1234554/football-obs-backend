package com.github.baek.footballobsbackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.error.ErrorResult;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.ConsumptionProbe;   // 추가
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;         // 추가

@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    /*
     * Rate limit 정책 상수.
     *
     * capacity 15:
     *  - 한 IP가 순간적으로 최대 15번까지 burst 요청 가능.
     *
     * refill 1 token / 4 seconds:
     *  - 토큰이 0개인 상태라면 다음 1토큰이 생기기까지 최대 4초가 걸린다.
     *  - 프런트가 "몇 초 뒤 재시도"를 정확히 계산할 수 있도록
     *    429 응답 헤더에 남은 대기시간도 함께 내려준다.
     */
    private static final long RATE_LIMIT_CAPACITY = 15L;
    private static final long RATE_LIMIT_REFILL_TOKENS = 1L;
    private static final Duration RATE_LIMIT_REFILL_PERIOD = Duration.ofSeconds(4);

    /*
     * 프런트가 읽을 응답 헤더 이름.
     *
     * Retry-After:
     *  - HTTP 표준 헤더.
     *  - 초(second) 단위라서 브라우저/프록시/클라이언트가 이해하기 쉽다.
     *
     * X-Retry-After-Millis:
     *  - 프런트에서 setTimeout 등에 바로 쓰기 쉽도록 ms 단위도 같이 내려준다.
     *
     * X-RateLimit-Limit / Remaining:
     *  - 디버깅 및 모니터링용.
     *  - 없어도 동작은 가능하지만 있으면 프런트/로그 확인이 편하다.
     */
    private static final String HEADER_RETRY_AFTER = "Retry-After";
    private static final String HEADER_RETRY_AFTER_MILLIS = "X-Retry-After-Millis";
    private static final String HEADER_RATE_LIMIT_LIMIT = "X-RateLimit-Limit";
    private static final String HEADER_RATE_LIMIT_REMAINING = "X-RateLimit-Remaining";
    private static final String HEADER_CACHE_CONTROL = "Cache-Control";

    /*
     * IP별 버킷 저장소.
     *
     * 왜 ConcurrentHashMap이 아닌 Caffeine인가?
     *  - ConcurrentHashMap은 한 번 put된 key를 자동으로 지워주지 않는다.
     *    -> 시간이 지날수록 본 적 있는 IP가 계속 누적되어 메모리 누수.
     *    (각 Bucket의 capacity 15는 "토큰 개수" 상한일 뿐, "키 개수"와는 무관)
     *  - Caffeine은 in-memory 캐시 라이브러리. TTL/최대 크기 기반 자동 evict 제공.
     *
     * expireAfterAccess(10분):
     *  - 마지막 read/write 이후 10분간 접근 없으면 해당 엔트리 제거.
     *  - 폴링 간격이 1분이라 10분 비활동이면 사실상 떠난 클라이언트로 간주 가능.
     *  - 다시 들어와도 새 버킷(토큰 풀충전)을 받으므로 사용자 입장에선 패널티 없음.
     *
     * maximumSize(10_000):
     *  - TTL이 정상 동작하면 거의 닿지 않는 안전장치 (트래픽 폭주 대비).
     *  - 한도 초과 시 Caffeine이 W-TinyLFU(LRU 변형) 정책으로 오래된 엔트리부터 evict.
     *
     * evict는 별도 스레드가 아니라 read/write 시점에 lazy하게 처리됨 (Bucket4j와 같은 방식).
     */
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .expireAfterAccess(Duration.ofMinutes(10))
            .maximumSize(10_000)
            .build();
    // ObjectMapper - JSON 문자열 생성기
    private final ObjectMapper objectMapper = new ObjectMapper()
            // Instant 타입을 JSON으로 올바르게 직렬화 해주는 모듈.
            .findAndRegisterModules();

    private Bucket createBucket() {
        return Bucket.builder()
                .addLimit(
                        Bandwidth.builder()
                                .capacity(RATE_LIMIT_CAPACITY)                      // 버킷 최대 용량
                                .refillGreedy(RATE_LIMIT_REFILL_TOKENS, RATE_LIMIT_REFILL_PERIOD) // 4초마다 1개 충전
                                .build()
                )
                .build();
    }

    /*
     * 정확한 설명:
     *  - 처음에는 토큰 15개로 시작한다.
     *  - 요청 1회마다 토큰 1개를 소모한다.
     *  - 이후 4초마다 토큰이 1개씩 다시 찬다.
     *  - 토큰은 최대 15개를 넘지 않는다.
     *
     * 즉 "4초마다 통 전체가 다시 가득 찬다"가 아니라,
     * "4초마다 1개씩 회복된다"가 맞다.
     */

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 요청자의 IP 주소 추출
        String ip = request.getRemoteAddr();

        // 해당 IP의 버킷이 없으면 새로 생성, 있으면 기존 버킷 사용
        // Caffeine Cache.get(key, mappingFunction):
        //   - ConcurrentHashMap.computeIfAbsent와 동일한 의미 (key 없을 때만 mappingFunction 호출)
        //   - 차이점: 내부적으로 access time을 갱신해서 expireAfterAccess TTL이 리셋됨
        //   - 즉 "이 IP가 방금 요청했다"는 사실이 캐시 만료 타이머를 연장시킴
        Bucket bucket = buckets.get(ip, k -> createBucket());

        /*
         * 단순 boolean이 아니라 ConsumptionProbe를 받는다.
         *
         * probe 안에는 다음 정보가 들어 있다:
         *  - isConsumed()              : 이번 요청이 통과했는지
         *  - getRemainingTokens()     : 남은 토큰 수
         *  - getNanosToWaitForRefill(): 다음 토큰이 생길 때까지 남은 시간(ns)
         *
         * 프런트에 "정확히 몇 ms 뒤 재시도하라"를 알려주려면
         * tryConsume(...) 대신 이 메서드를 써야 한다.
         */
        ConsumptionProbe probe = bucket.tryConsumeAndReturnRemaining(1);

        if (probe.isConsumed()) {
            // 성공 응답에도 현재 limit 상태를 헤더로 내려주면 디버깅에 도움이 된다.
            response.setHeader(HEADER_RATE_LIMIT_LIMIT, String.valueOf(RATE_LIMIT_CAPACITY));
            response.setHeader(HEADER_RATE_LIMIT_REMAINING, String.valueOf(probe.getRemainingTokens()));
            return true;
        }

        // 토큰 없음 -> 429 Too Many Requests 반환
        writeRateLimitExceededResponse(request, response, ip, probe);
        return false;
    }

    /**
     * 429 응답 헤더를 세팅한다.
     *
     * 왜 Retry-After와 X-Retry-After-Millis를 둘 다 내려주나?
     *  - Retry-After는 표준 헤더라 범용성이 좋다.
     *  - 다만 초 단위라서 프런트 setTimeout에는 해상도가 거칠다.
     *  - 그래서 프런트 전용으로 ms 헤더도 같이 내리면 구현이 쉬워진다.
     */
    private void writeRateLimitHeaders(HttpServletResponse response, ConsumptionProbe probe) {
        long waitNanos = Math.max(0L, probe.getNanosToWaitForRefill());
        long waitMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(waitNanos));

        /*
         * Retry-After는 초 단위 정수여야 하므로 올림 처리한다.
         *
         * 예:
         *  - 1ms 남음  -> 1초
         *  - 3999ms 남음 -> 4초
         *  - 4000ms 남음 -> 4초
         */
        long retryAfterSeconds = Math.max(1L, (waitMillis + 999L) / 1000L);

        response.setHeader(HEADER_RETRY_AFTER, String.valueOf(retryAfterSeconds));
        response.setHeader(HEADER_RETRY_AFTER_MILLIS, String.valueOf(waitMillis));
        response.setHeader(HEADER_RATE_LIMIT_LIMIT, String.valueOf(RATE_LIMIT_CAPACITY));
        response.setHeader(HEADER_RATE_LIMIT_REMAINING, String.valueOf(probe.getRemainingTokens()));

        // 429 응답은 중간 캐시가 붙잡지 않게 no-store를 명시한다.
        response.setHeader(HEADER_CACHE_CONTROL, "no-store");
    }

    /**
     * rate limit 초과 시 429 JSON 응답을 직접 작성한다.
     *
     * 인터셉터는 컨트롤러 진입 전에 실행되므로
     * @ControllerAdvice가 개입할 수 없다.
     * 따라서 여기서 응답 헤더/바디를 직접 써야 한다.
     */
    private void writeRateLimitExceededResponse(HttpServletRequest request,
                                                HttpServletResponse response,
                                                String ip,
                                                ConsumptionProbe probe) throws Exception {

        writeRateLimitHeaders(response, probe);

        ErrorCode errorCode = ErrorCode.RATE_LIMIT_EXCEEDED;
        ErrorResult errorResult = new ErrorResult(
                errorCode.name(),
                errorCode.getMessage(),
                errorCode.getStatus().value(),
                request.getRequestURI(),
                Instant.now()
        );
        response.setStatus(errorCode.getStatus().value());
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(errorResult));

        long waitMillis = Math.max(0L, TimeUnit.NANOSECONDS.toMillis(probe.getNanosToWaitForRefill()));
        log.info("[LIMIT_EXCEEDED] Rate limit exceeded for IP: {}, retryAfterMs={}", ip, waitMillis);
    }
}
