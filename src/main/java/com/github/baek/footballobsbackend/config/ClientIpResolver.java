package com.github.baek.footballobsbackend.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import lombok.extern.slf4j.Slf4j;

/**
 * 서버 입구와 외부 호출 제한이 같은 방문자 IP를 사용하도록 하는 공통 규칙.
 * 전달 헤더는 Tomcat이 신뢰 프록시 설정에 따라 해석한다.
 * 애플리케이션에서 원본 헤더를 다시 읽으면 위조된 앞쪽 값이 사용될 수 있다.
 */
@Slf4j
public final class ClientIpResolver {
    private ClientIpResolver() {}

    /** Tomcat이 검증한 주소를 서버 입구와 외부 호출 제한에서 함께 사용한다. */
    public static String resolve(HttpServletRequest request) {
        String selectedIp = request.getRemoteAddr();
        // 진단 경로의 요청만 출력하여 일반 방문자의 IP가 진단 로그에 남지 않게 한다.
        if (log.isDebugEnabled() && "/api/__ip_check__".equals(request.getRequestURI())) {
            log.debug("[CLIENT_IP_DIAGNOSTIC] case={} remoteAddr={} selectedIp={} xForwardedFor={} cfConnectingIp={} trueClientIp={}",
                    logValue(request.getParameter("case")), logValue(request.getRemoteAddr()), logValue(selectedIp),
                    logValue(String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")))),
                    logValue(request.getHeader("CF-Connecting-IP")), logValue(request.getHeader("True-Client-IP")));
        }
        return selectedIp;
    }

    // 전달 헤더는 외부 입력이므로 줄바꿈과 과도한 길이로 로그를 오염시키지 않게 한다.
    private static String logValue(String value) {
        if (value == null || value.isEmpty()) return "-";
        String bounded = value.substring(0, Math.min(value.length(), 512));
        return bounded.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "_");
    }
}
