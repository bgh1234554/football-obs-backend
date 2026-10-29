package com.github.baek.footballobsbackend.config;

import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 빈 정규식이 모든 프록시를 신뢰하게 되는 Tomcat 설정 실수를 시작 시 차단한다. */
@Configuration(proxyBeanMethods = false)
public class TrustedProxyConfig {
    @Bean
    WebServerFactoryCustomizer<TomcatServletWebServerFactory> validateTrustedProxies(
            @Value("${server.tomcat.remoteip.internal-proxies:(?!)}") String proxies) {
        if (proxies.isBlank()) {
            throw new IllegalArgumentException("TRUSTED_PROXY_REGEX must not be blank; use (?!) to trust no proxies");
        }
        Pattern.compile(proxies);
        return factory -> {};
    }
}
