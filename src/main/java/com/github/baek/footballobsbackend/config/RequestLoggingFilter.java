package com.github.baek.footballobsbackend.config;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/*
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [요청 단위 로그 필터] 모든 /api/** 요청에 requestId를 부여하고, 요청 1건당
 * 결과 로그를 정확히 1줄 남긴다.
 *
 * 배경: 이 필터가 생기기 전에는 GlobalExceptionAdvice가 예외를 잡을 때만
 * WARN/ERROR 로그가 남았고, 정상적으로 끝난 요청은 로그에 흔적이 전혀
 * 없었다. 그래서 "요청이 들어왔는지 / 얼마나 걸렸는지 / 무슨 상태코드로
 * 끝났는지"를 보여주는 로그가 이 필터의 역할이다.
 * 설계 배경·레벨 정책·검토한 대안은 docs/logging.md 참고.
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 * [필터가 매 요청마다 거치는 순서]
 *
 * 0단계 — shouldNotFilter
 *   /api/**가 아닌 요청(정적 리소스, actuator 등)은 이 필터를 건너뛴다.
 *   RateLimitInterceptor가 /api/**에만 적용되는 것과 같은 범위를 맞춘 것.
 *
 * 1단계 — requestId 발급 + MDC 주입 (doFilterInternal 진입 시)
 *   MDC(Mapped Diagnostic Context)는 SLF4J/Logback이 제공하는 "스레드 하나당
 *   붙는 key-value 저장소"다. UUID 앞 8자리를 requestId로 쓰고
 *   MDC.put("requestId", ...)으로 저장한다.
 * 
 * MDC란?
 * MDC(Mapped Diagnostic Context) - SLF4J/Logback이 제공하는 스레드 하나당 붙는 key-value 로컬 저장소
 * MDC.put("requestId", "a1b2c3d4")로 넣어두면, 그 이후 같은 스레드에서 찍히는 모든 로그 줄이 코드 변경 없이 이 값을 참조할 수 있다.
 * (RateLimitInterceptor → Controller → Service → ApiFootballClient → 예외 시 GlobalExceptionAdvice 여기 모두에서 일관되게)
 * application.yaml의 %X{requestId}가 바로 이 저장소에서 값을 꺼내는 문법이다.
 * 스레드가 끝나기 전에 MDC.remove(...)로 지워야 하는데, Tomcat이 스레드를 재사용하기 때문이다.
 * (안 지우면 다음 요청이 이전 값을 물고 로그를 찍음)
 *
 * 2단계 — chain.doFilter(request, response)
 *   다음 단계(인터셉터 → 컨트롤러 → 서비스 → 필요하면 ApiFootballClient까지)로
 *   처리를 넘긴다. 이 호출은 그 처리가 전부 끝날 때까지 되돌아오지 않으므로
 *   (동기 처리라 같은 스레드 안에서 순서대로 실행됨), 아래 3단계에서 재는
 *   시간은 "요청 전체 처리 시간"이 된다.
 *
 * 3단계 — finally: 결과 로그 1줄
 *   1단계에서 기록해둔 시작 시각부터 지금까지 걸린 시간(durationMs)과 최종
 *   응답 상태코드를 INFO로 남긴다. response.getStatus()는 컨트롤러가 정상
 *   응답한 경우든, GlobalExceptionAdvice가 예외를 잡아 세팅한 경우든 항상
 *   최종 상태코드를 반영한다. try 블록 안에서 예외가 던져지고 다시 던져지는
 *   경우가 생겨도 finally는 항상 실행되므로 로그가 누락되지 않는다.
 *
 * 4단계 — MDC.remove(...)
 *   Tomcat은 요청 처리 스레드를 풀링해서 재사용한다. 여기서 정리를 안 하면
 *   다음 요청이 이전 요청의 requestId를 그대로 물고 로그를 찍는 사고가 난다.
 *   그래서 반드시 finally 블록 "안"에서, 로그를 남긴 바로 다음 줄에 정리한다.
 * ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
 */
@Slf4j
@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID = "requestId";

    /** 0단계: /api/**가 아닌 요청은 이 필터를 건너뛴다. */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        // 1단계: requestId 발급 + MDC 주입.
        // UUID 전체(36자)는 로그 한 줄에 넣기엔 너무 길어서 앞 8자리만 사용한다.
        // 현재의 프로젝트 트래픽 규모에서 8자리 충돌 가능성은 실질적으로 무시할 수준 — 
        // 완전히 배제하고 싶으면 추후에 substring 없이 풀 UUID를 쓰면 된다.
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_REQUEST_ID, requestId);

        long startedAt = System.nanoTime();
        try {
            // 2단계: 다음 단계로 처리를 넘긴다. 여기서 인터셉터/컨트롤러/서비스가
            // 전부 실행되고, 이 줄이 끝나야 아래 finally로 넘어간다.
            chain.doFilter(request, response);
        } finally {
            // 3단계: 처리 완료 — 결과 로그 1줄.
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("request method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);

            // 4단계: MDC 정리 (Tomcat 스레드 재사용으로 인한 requestId 누수 방지).
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
