package com.github.baek.footballobsbackend.config;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 서버 입구와 외부 호출 제한이 같은 방문자 IP를 사용하도록 하는 공통 규칙.
 * 전달 헤더는 Tomcat이 신뢰 프록시 설정에 따라 해석한다.
 * 애플리케이션에서 원본 헤더를 다시 읽으면 위조된 앞쪽 값이 사용될 수 있다.
 */
public final class ClientIpResolver {
    private ClientIpResolver() {}

    /** Tomcat이 검증한 주소를 서버 입구와 외부 호출 제한에서 함께 사용한다. */
    public static String resolve(HttpServletRequest request) {
        return request.getRemoteAddr();
    }
}
