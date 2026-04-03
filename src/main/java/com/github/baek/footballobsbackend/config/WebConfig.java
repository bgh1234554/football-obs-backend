package com.github.baek.footballobsbackend.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 전역 설정.
 *
 * [CORS 정책]
 * 실운영에서는 BunnyCDN Pull Zone의 Allowed Referers / IP Whitelist 기능으로 접근을 제어함.
 * 백엔드 자체 CORS 필터는 개발 편의를 위해 전체 허용("*")으로 두고,
 * 도메인 제한은 CDN 레이어에서 담당.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 1. 모든 /api/** 경로에 대해 모든 출처 허용 (CDN 레이어에서 접근 제어)
        registry.addMapping("/api/**")
                .allowedOrigins("*")
                .allowedMethods("GET");
    }
}