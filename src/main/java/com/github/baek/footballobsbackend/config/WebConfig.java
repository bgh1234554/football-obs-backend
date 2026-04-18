package com.github.baek.footballobsbackend.config;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 전역 설정.
 *
 * [CORS 정책]
 * 실운영에서는 BunnyCDN Pull Zone의 Allowed Referers / IP Whitelist 기능으로 접근을 제어함.
 * 백엔드 자체 CORS 필터는 개발 시에는 편의를 위해 전체 허용("*")으로 두고,
 * 서비스 런칭 후 도메인 제한은 CDN 레이어에서 화이트리스트 추가 및 제한 추가
 */
@RequiredArgsConstructor
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final RateLimitInterceptor rateLimitInterceptor;

    @Value("${cors.allowed-origins}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 1. 모든 /api/** 경로에 대해 모든 출처 허용 (CDN 레이어에서 접근 제어)
        // CORS -> 다른 웹사이트에서 JS로 무단 호출 방어 역할
        registry.addMapping("/api/**")
                .allowedOrigins("*")
//                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET");
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/api/**");  // /api/** 경로에만 rate limit 적용
        // /actuator/health 같은 건 적용 안 됨
    }
}
/*
방어책, 막는 것
CORS 도메인 제한, 다른 웹사이트에서 JS로 무단 호출
Bucket4j, 어디서든 과도한 반복 호출
BunnyCDN IP 화이트리스트, BunnyCDN 직접 호출
 */