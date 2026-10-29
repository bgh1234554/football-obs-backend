package com.github.baek.footballobsbackend.config;

import jakarta.servlet.http.HttpServletResponse;

/**
 * 두 제한 단계가 프런트에 같은 헤더 계약을 제공한다.
 * 정상 응답의 값은 서버 입구 버킷이며 외부 호출 제한으로 거절되면 그 단계의 값으로 덮어쓴다.
 * scope가 server / upstream-ip / upstream-global 중 어느 제한인지 구분해 준다.
 */
public final class RateLimitHeaders {
    private RateLimitHeaders() {}

    /**
     * @param response 헤더를 기록할 현재 응답
     * @param scope 적용 단계와 버킷 종류
     * @param limit 그 버킷의 순간 허용량
     * @param remaining 그 버킷의 잔여량, 외부 호출 거절은 0
     * @param retryMillis 거절 시 대기시간, 정상 통과는 null
     */
    public static void write(HttpServletResponse response, String scope, long limit, long remaining, Long retryMillis) {
        response.setHeader("X-RateLimit-Scope", scope);
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(remaining));
        // 정상 통과에는 재시도·캐시 금지 헤더를 붙이지 않는다.
        if (retryMillis != null) {
            // 표준 헤더는 초 단위 정수로 올림하고, 프런트에는 정밀한 ms 값도 함께 준다.
            response.setHeader("Retry-After", String.valueOf(Math.max(1L, (retryMillis + 999L) / 1000L)));
            response.setHeader("X-Retry-After-Millis", String.valueOf(retryMillis));
            // 429를 브라우저/CDN이 캐시하면 회복 뒤에도 차단 응답이 남을 수 있다.
            response.setHeader("Cache-Control", "no-store");
        }
    }
}
