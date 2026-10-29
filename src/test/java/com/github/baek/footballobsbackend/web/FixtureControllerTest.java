package com.github.baek.footballobsbackend.web;

import com.github.baek.footballobsbackend.config.RateLimitInterceptor;
import com.github.baek.footballobsbackend.config.WebConfig;
import com.github.baek.footballobsbackend.service.FixtureService;
import com.github.baek.footballobsbackend.service.HeadtoheadService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.github.baek.footballobsbackend.config.RateLimitTestSettings.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(FixtureController.class)
@Import(WebConfig.class)
@TestPropertySource(properties = "cors.allowed-origins=https://football-obs-frontend.vercel.app,"
        + "https://football-obs-frontend-git-feature-bgh1234554-obso-verlay.vercel.app,"
        + "https://football-obs-frontend-git-feature-incvel-obso-verlay.vercel.app,"
        + "https://football-obs-frontend-git-develop-obso-verlay.vercel.app,"
        + "http://localhost:5500,http://127.0.0.1:5500,http://localhost:8080,http://127.0.0.1:8080")
class FixtureControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean FixtureService service;
    @MockitoBean HeadtoheadService headtohead;
    @MockitoBean RateLimitInterceptor limiter;

    @BeforeEach
    void allowRequests() throws Exception {
        when(limiter.preHandle(any(), any(), any())).thenReturn(true);
    }

    @Test
    void missingFixtureReturns404WithoutSuccessCacheHeader() throws Exception {
        when(service.getFixture(999L)).thenThrow(new com.github.baek.footballobsbackend.error.ApiException(
                com.github.baek.footballobsbackend.error.ErrorCode.FIXTURE_NOT_FOUND));
        mvc.perform(get("/api/fixtures/999"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("Cache-Control"))
                .andExpect(jsonPath("$.code").value("FIXTURE_NOT_FOUND"));
    }

    @Test
    void routesManualRefreshWithoutChangingBrowserCachePolicy() throws Exception {
        mvc.perform(get("/api/fixtures/1")).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", "max-age=15"));
        verify(service).getFixture(1);
        mvc.perform(get("/api/fixtures/1").header("X-Force-Refresh", "true"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "max-age=15"));
        verify(service).getFixture(1, true);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://football-obs-frontend.vercel.app",
            "https://football-obs-frontend-git-feature-bgh1234554-obso-verlay.vercel.app",
            "https://football-obs-frontend-git-feature-incvel-obso-verlay.vercel.app",
            "https://football-obs-frontend-git-develop-obso-verlay.vercel.app",
            "http://localhost:5500", "http://127.0.0.1:5500",
            "http://localhost:8080", "http://127.0.0.1:8080"})
    void allowsBrowserPreflightForRefreshHeader(String origin) throws Exception {
        mvc.perform(options("/api/fixtures/1")
                        .header("Origin", origin)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "x-force-refresh"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Headers", "x-force-refresh"))
                .andExpect(header().string("Access-Control-Allow-Origin", origin));
        verifyNoInteractions(service);
    }
    @ParameterizedTest
    @ValueSource(strings = {"https://untrusted.example", "http://localhost:5501",
            "https://football-obs-backend.onrender.com"})
    void rejectsUnlistedOrigins(String origin) throws Exception {
        mvc.perform(get("/api/fixtures/1").header("Origin", origin))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        verifyNoInteractions(service);
    }

    @Test
    void exposesRetryHeadersEvenWhenRateLimited() throws Exception {
        when(limiter.preHandle(any(), any(), any())).thenAnswer(invocation -> {
            jakarta.servlet.http.HttpServletResponse response = invocation.getArgument(1);
            response.setStatus(429);
            response.setHeader("Retry-After", String.valueOf((value("response.retry-ms")+999)/1000));
            response.setHeader("X-Retry-After-Millis", String.valueOf(value("response.retry-ms")));
            return false;
        });
        mvc.perform(get("/api/fixtures/1").header("Origin", "http://localhost:5500"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5500"))
                .andExpect(header().string("Access-Control-Expose-Headers",
                        "Retry-After, X-Retry-After-Millis, X-RateLimit-Limit, X-RateLimit-Remaining, X-RateLimit-Scope"))
                .andExpect(header().string("Retry-After", String.valueOf((value("response.retry-ms")+999)/1000)));
        verifyNoInteractions(service);
    }

    @Test
    void upstreamLimitPreservesCorsRetryHeadersAndErrorBody() throws Exception {
        when(service.getFixture(1L)).thenThrow(new com.github.baek.footballobsbackend.error.UpstreamRateLimitException("upstream-global",value("response.limit"),value("response.retry-ms")));
        mvc.perform(get("/api/fixtures/1").header("Origin","http://localhost:5500"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("X-RateLimit-Scope","upstream-global"))
                .andExpect(header().string("X-RateLimit-Limit",String.valueOf(value("response.limit"))))
                .andExpect(header().string("Retry-After",String.valueOf((value("response.retry-ms")+999)/1000)))
                .andExpect(header().string("X-Retry-After-Millis",String.valueOf(value("response.retry-ms"))))
                .andExpect(header().string("Cache-Control","no-store"))
                .andExpect(header().string("Access-Control-Allow-Origin","http://localhost:5500"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT_EXCEEDED"));
    }

}
