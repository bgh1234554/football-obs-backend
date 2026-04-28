package com.github.baek.footballobsbackend.config;

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
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;

@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

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
    //ObjectMapper - JSON 문자열 생성기
    private final ObjectMapper objectMapper = new ObjectMapper()
            //Instant 타입을 JSON으로 올바르게 직렬화 해주는 모듈.
            .findAndRegisterModules();

    private Bucket createBucket() {
        return Bucket.builder()
                .addLimit(
                        Bandwidth.builder()
                                .capacity(15)                        // 버킷 최대 용량
                                .refillGreedy(1, Duration.ofSeconds(4)) // 4초마다 1개씩 충전
                                .build()
                )
                .build();
    }
    // 쉽게 말하면: 4초마다 최대 용량 15개짜리 토큰 통을 가득 채움.
    // 처음에는 가득 찬 상태로 시작, 요청할 때마다 토큰 1개 소모, 토큰 다 떨어지면 429 반환
    // Lazy 방식이라서 요청이 들어올때 시간을 계산해서 bucket에 토큰을 채운다.

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

        // 토큰 1개 소모 시도
        if (bucket.tryConsume(1)) {
            return true;  // 토큰 있음 → 요청 통과
        }

        // 토큰 없음 → 429 Too Many Requests 반환
        /*
        인터셉터에서 JSON 바디를 직접 쓰는 패턴은 흔하게 사용한다.
        Spring Security의 AuthenticationEntryPoint나 AccessDeniedHandler도 같은 방식이다.

        다만 이 경우처럼 GlobalExceptionAdvice 같은 @ControllerAdvice가 이미 있으면,
        인터셉터는 컨트롤러 진입 전에 막으니까 @ControllerAdvice가 개입할 수 없다.
        그래서 response.getWriter().write(...) 로 직접 내려주는 방법밖에 없다.
         */
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
        log.info("[LIMIT_EXCEEDED] Rate limit exceeded for IP: {}", ip);
        return false;
    }
}
