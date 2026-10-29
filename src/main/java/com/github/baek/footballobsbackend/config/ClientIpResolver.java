package com.github.baek.footballobsbackend.config;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.net.InetAddress;
import java.net.UnknownHostException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 서버 입구와 외부 호출 제한이 같은 방문자 IP를 사용하도록 하는 공통 규칙.
 * Render의 공개 진입 경로에서만 CF-Connecting-IP를 사용한다.
 * X-Forwarded-For와 True-Client-IP는 버킷 키로 사용하지 않는다.
 */
@Slf4j
@Component
public final class ClientIpResolver {
    private final boolean render;

    public ClientIpResolver(@Value("${RENDER:false}") boolean render) {
        this.render = render;
    }

    /** Render 외 환경이나 전달 주소가 없거나 잘못된 경우에는 연결 상대 주소를 사용한다. */
    public String resolve(HttpServletRequest request) {
        String selectedIp = request.getRemoteAddr();
        if (render) {
            var headers = Collections.list(request.getHeaders("CF-Connecting-IP"));
            String forwardedIp = headers.size() == 1 ? numericIp(headers.getFirst()) : null;
            if (forwardedIp != null) selectedIp = forwardedIp;
        }
        // 진단 경로의 요청만 출력하여 일반 방문자의 IP가 진단 로그에 남지 않게 한다.
        if (log.isDebugEnabled() && "/api/__ip_check__".equals(request.getRequestURI())) {
            log.debug("[CLIENT_IP_DIAGNOSTIC] case={} remoteAddr={} selectedIp={} xForwardedFor={} cfConnectingIp={} trueClientIp={}",
                    logValue(request.getParameter("case")), logValue(request.getRemoteAddr()), logValue(selectedIp),
                    logValue(String.join(",", Collections.list(request.getHeaders("X-Forwarded-For")))),
                    logValue(request.getHeader("CF-Connecting-IP")), logValue(request.getHeader("True-Client-IP")));
        }
        return selectedIp;
    }

    // 숫자 IP만 허용하여 DNS 조회와 임의 문자열별 버킷 생성을 막고 표기를 정규화한다.
    private static String numericIp(String value) {
        if (value == null) return null;
        String ip = value.strip();
        if (ip.length() > 45) return null;
        boolean ipv4 = ip.matches("(?:[0-9]{1,3}\\.){3}[0-9]{1,3}");
        boolean ipv6 = ip.contains(":") && ip.matches("[0-9a-fA-F:.]+");
        if (!ipv4 && !ipv6) return null;
        try {
            return InetAddress.getByName(ip).getHostAddress();
        } catch (UnknownHostException e) {
            return null;
        }
    }

    // 전달 헤더는 외부 입력이므로 줄바꿈과 과도한 길이로 로그를 오염시키지 않게 한다.
    private static String logValue(String value) {
        if (value == null || value.isEmpty()) return "-";
        String bounded = value.substring(0, Math.min(value.length(), 512));
        return bounded.replaceAll("[\\p{Cntrl}\\u2028\\u2029]", "_");
    }
}
