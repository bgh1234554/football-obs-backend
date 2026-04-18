package com.github.baek.footballobsbackend.config;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitInterceptor implements HandlerInterceptor {

    // IP 주소별로 버킷을 저장하는 Map
    // ConcurrentHashMap: 멀티스레드 환경(여러 요청 동시 처리)에서 안전한 Map
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

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
    // 쉽게 말하면: 1분마다 30개짜리 토큰 통이 가득 채워짐
    // 요청할 때마다 토큰 1개 소모, 토큰 다 떨어지면 429 반환

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
        response.setStatus(429);
        return false;  // 컨트롤러로 요청 안 넘어감
    }
}
