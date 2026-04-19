package com.github.baek.footballobsbackend.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.error.ErrorResult;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
@Slf4j
public class RateLimitInterceptor implements HandlerInterceptor {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
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

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws Exception {

        // 요청자의 IP 주소 추출
        String ip = request.getRemoteAddr();

        // 해당 IP의 버킷이 없으면 새로 생성, 있으면 기존 버킷 사용
        // computeIfAbsent: key(ip)가 없을 때만 createBucket() 호출
        Bucket bucket = buckets.computeIfAbsent(ip, k -> createBucket());

        // 토큰 1개 소모 시도
        if (bucket.tryConsume(1)) {
            return true;  // 토큰 있음 → 요청 통과
        }

        // 토큰 없음 → 429 Too Many Requests 반환
        /*
        인터셉터에서 JSON 바디를 직접 쓰는 패턴은 흔하게 사용한다.
        Spring Security의 AuthenticationEntryPoint나 AccessDeniedHandler도 같은 방식입니다.

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
