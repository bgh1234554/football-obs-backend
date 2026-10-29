package com.github.baek.footballobsbackend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 전역 설정.
 * CORS 허용 Origin은 환경변수로 지정하며, 직접 호출의 인증은 대신하지 않는다.
 */
@RequiredArgsConstructor
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET")
                // 요청 방향(브라우저 → 백엔드): X-Force-Refresh 등 요청 헤더의 사용을 허용한다.
                // *는 헤더 이름의 전체 허용이며, 허용 사이트는 allowedOrigins가 별도로 제한한다.
                .allowedHeaders("*")
                // 응답 방향(백엔드 → 브라우저): 아래 응답 헤더를 프런트 JS에서 읽을 수 있게 한다.
                // 헤더 전송 자체를 허용하는 설정은 아니며, 429 재시도 대기시간과 제한 상태를 JS에 노출한다.
                .exposedHeaders("Retry-After", "X-Retry-After-Millis",
                        "X-RateLimit-Limit", "X-RateLimit-Remaining", "X-RateLimit-Scope");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");  // /api/** 경로에만 rate limit 적용
        // /actuator/health 같은 건 적용 안 됨
    }
}
