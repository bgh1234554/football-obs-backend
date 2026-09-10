# 로그 설계

- 설계 배경 (2026년 9월 기준): 기존 로그는 `GlobalExceptionAdvice`(예외 발생 시 WARN/ERROR)와 각 계층의 개별 INFO 로그(`ApiFootballClient`의 호출 시작 로그, `FixtureService`/`KoResolver`의 `[KO_NAME_NEEDED]` 등 데이터 품질 로그)만 있고, "요청이 정상적으로 몇 ms 걸려 처리됐는지"를 보여주는 로그가 없었음. 오류가 안 나면 그 요청이 있었다는 흔적조차 로그에 안 남는 구조였음.

## 관련 파일

| 파일 | 역할 |
| --- | --- |
| `config/RequestLoggingFilter.java` | 요청 단위 requestId 발급 + 완료 로그 1줄 |
| `config/RateLimitInterceptor.java` | rate limit 초과 시에만 IP를 남김. `resolveClientIp()` 관련 변경은 [rate-limiting.md](rate-limiting.md)/[client-ip-resolution.md](client-ip-resolution.md) 참고 (이 문서 범위 밖) |
| `client/ApiFootballClient.java` | 기존 호출 시작 로그 + 완료 시 소요시간 로그 |
| `error/GlobalExceptionAdvice.java` | 기존. 예외 발생 시 WARN/ERROR (변경 없음) |
| `service/FixtureService.java`, `util/KoResolver.java` | 기존 `[KO_NAME_NEEDED]` 등 데이터 품질 태그 로그 — **레벨 변경 금지** |
| `application.yaml` | `logging.pattern.console`, `logging.level` 추가 |

## 로그 흐름

```
요청 진입
  -> RequestLoggingFilter        MDC에 requestId 주입 (servlet filter, 컨트롤러/인터셉터보다 앞단)
  -> RateLimitInterceptor        기존 그대로, 초과시만 INFO
  -> Controller -> Service       DEBUG 레벨 로그. 조회 시작/종료 표시. application.yaml에서 패키지 레벨 DEBUG로 올려서 확인 가능함
  -> ApiFootballClient           호출마다 시작 INFO + 완료 INFO(상태/소요시간)
  -> GlobalExceptionAdvice       예외 시 WARN/ERROR
  응답 종료
  -> RequestLoggingFilter        finally 블록에서 method/path/status/durationMs 1줄 INFO
```

핵심: 요청 하나가 여러 개의 upstream 호출을 만들 수 있음 (예: `GET /api/fixtures/{id}` 하나가 내부적으로 `/fixtures`, `/injuries`, 부상 선수별 `/players/profiles` 여러 번을 호출 — [`FixtureService.getFixture`](../src/main/java/com/github/baek/footballobsbackend/service/FixtureService.java) 참고). 이 모든 upstream 호출 로그와 최종 요청 완료 로그가 **같은 requestId**를 공유하므로, 전체 처리 시간 중 얼마가 외부 API 대기 시간이고 얼마가 우리 쪽 처리(JSON 파싱, CSV lookup, DTO 조립) 시간인지 로그만으로 구분 가능.

## 로그 레벨 정책

| 위치 | 내용 | 레벨 | 비고 |
| --- | --- | --- | --- |
| `RequestLoggingFilter` | method, path, status, durationMs | INFO | 요청 1건당 정확히 1줄. IP는 **의도적으로 미포함** (아래 "IP 로깅" 참고) |
| `ApiFootballClient` 호출 시작 | 어떤 엔드포인트를 호출하는지 | INFO | 기존 유지 |
| `ApiFootballClient` 호출 완료 | 상태, durationMs | INFO | 기존에 없던 부분. 신규 추가 필요 |
| `ApiFootballClient` 업스트림 오류 | status, body | WARN | 기존 유지 |
| `FixtureService`/`KoResolver` 데이터 품질 태그 | `[KO_NAME_NEEDED]`, `[LOGO_NEEDED]` 등 | INFO | **절대 변경하지 않음** — Render 로그 키워드 그렙 → CSV 업데이트 → 재배포 운영 흐름이 이 레벨/포맷에 의존함 |
| `FixtureService`/`PlayerService`/`HeadtoheadService` 조회 시작/종료 | fixtureId 등 식별자, 결과 건수, durationMs | DEBUG | 적용됨. 기본 미노출, 특정 요청 재현 필요할 때만 해당 패키지 레벨을 임시로 올려서 사용 |
| `GlobalExceptionAdvice` | 예외 코드, path | WARN / ERROR | 기존 유지 |
| `RateLimitInterceptor` | 초과 IP | INFO | 기존 유지 (아래 "IP 로깅" 참고) |

root 레벨은 INFO로 두고, DEBUG 라인은 평소엔 안 보이게 하되 문제 재현이 필요할 때만 아래처럼 특정 패키지만 올린다:

```yaml
logging:
  level:
    com.github.baek.footballobsbackend.service: DEBUG
```

## `application.yaml` 추가안

```yaml
logging:
  pattern:
    console: "%d{HH:mm:ss.SSS} %-5level [%X{requestId:-no-id}] %logger{36} - %msg%n"
  level:
    root: INFO
```

### 패턴 문자열 조각별 설명

| 조각 | 의미 |
| --- | --- |
| `%d{HH:mm:ss.SSS}` | 타임스탬프(밀리초까지) — 같은 requestId 로그 줄 사이의 시간차를 눈으로 확인하는 용도 |
| `%-5level` | 로그 레벨. `-5`는 "최소 5칸 왼쪽 정렬" — `INFO`/`WARN`/`ERROR` 길이가 달라도 다음 컬럼 시작 위치를 맞춰줌 |
| `[%X{requestId:-no-id}]` | `%X{key}`는 MDC(스레드별 key-value 저장소)에서 값을 꺼냄. `RequestLoggingFilter`가 요청마다 넣어둔 requestId가 여기 찍힘. `:-no-id`는 MDC에 값이 없을 때(앱 부팅 로그, `CsvUpdater` 등 요청 스코프 밖 실행)의 기본값 |
| `%logger{36}` | 로그를 찍은 클래스 풀네임, 최대 36자로 축약(패키지 경로만 줄어들고 클래스명은 안 잘림) |
| ` - %msg` | 실제 로그 메시지 |
| `%n` | 줄바꿈 |

Spring Boot 기본 콘솔 패턴(PID, 스레드명 등 포함)을 완전히 대체하는 설정이라, 스레드명이 안 보이는 대신 requestId가 보이는 트레이드오프임.

 * 예시 코드
```bash
[req-a1b2c3d4] Upstream call completed path=/fixtures?id=123 durationMs=120
[req-a1b2c3d4] Upstream call completed path=/injuries?ids=123 durationMs=45
[req-a1b2c3d4] Upstream call completed path=/players/profiles?player=874 durationMs=60
[req-a1b2c3d4] request method=GET path=/api/fixtures/123 status=200 durationMs=250
```

## `RequestLoggingFilter` 설계 예시

```java
package com.github.baek.footballobsbackend.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 모든 /api/** 요청에 requestId를 부여하고, 요청 1건당 결과 로그 1줄을 남긴다.
 * RateLimitInterceptor보다 앞단(서블릿 필터)에서 동작하므로 429로 막힌 요청도 기록된다.
 */
@Slf4j
@Component
@Order(1)
public class RequestLoggingFilter extends OncePerRequestFilter {

    private static final String MDC_REQUEST_ID = "requestId";

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String requestId = UUID.randomUUID().toString().substring(0, 8);
        MDC.put(MDC_REQUEST_ID, requestId);
        long startedAt = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
            log.info("request method={} path={} status={} durationMs={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), durationMs);
            MDC.remove(MDC_REQUEST_ID);
        }
    }
}
```

동기 호출(RestClient)이라 requestId를 서비스/클라이언트 계층에 인자로 넘길 필요 없음 — 같은 스레드에서 처리되므로 MDC 값이 자동으로 이어짐.

requestId는 `UUID.substring(0, 8)`로 8자만 사용. 이 프로젝트 트래픽 규모에서 충돌 가능성은 실질적으로 무시할 수준이나, 완전히 배제하려면 자르지 않고 풀 UUID를 쓰면 됨.

## `ApiFootballClient.fetchRoot` 변경안 (적용됨)

```java
private JsonNode fetchRoot(String path) {
    long startedAt = System.nanoTime();
    try {
        JsonNode result = restClient.get().uri(path).retrieve().body(JsonNode.class);
        log.info("Upstream call completed path={} durationMs={}",
                path, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
        return result;
    } catch (RestClientResponseException e) {
        log.warn("API Football upstream error: status={}, path={}, body={}",
                e.getStatusCode().value(), path, e.getResponseBodyAsString());
        throw new ApiException(ErrorCode.UPSTREAM_API_ERROR);
    }
}
```

## 예상 출력 예시

```
14:22:10.512 INFO  [a1b2c3d4] c.g.b.footballobsbackend.client.ApiFootballClient - Fetching fixture id=123
14:22:10.632 INFO  [a1b2c3d4] c.g.b.footballobsbackend.client.ApiFootballClient - Upstream call completed path=/fixtures?id=123 durationMs=120
14:22:10.633 INFO  [a1b2c3d4] c.g.b.footballobsbackend.client.ApiFootballClient - Fetching injuries fixtureId=123
14:22:10.678 INFO  [a1b2c3d4] c.g.b.footballobsbackend.client.ApiFootballClient - Upstream call completed path=/injuries?ids=123 durationMs=45
14:22:10.762 INFO  [a1b2c3d4] c.g.b.footballobsbackend.config.RequestLoggingFilter - request method=GET path=/api/fixtures/123 status=200 durationMs=250
```

전체 250ms 중 upstream 합계 165ms를 빼면 내부 처리(파싱/조립/CSV lookup)는 약 85ms. `grep a1b2c3d4`로 이 요청의 전체 흐름만 뽑아볼 수 있음(동시에 여러 기기가 요청해도 requestId는 스레드별로 고유하게 발급되므로 섞이지 않음).

## IP 로깅에 대한 원칙

IP 주소는 다른 정보와 결합 시 개인 식별이 가능해 개인정보보호법상 개인정보로 취급된다. 그래서 로그에 남기는 행위 자체가 무조건 위법은 아니지만, **목적이 명확하고 최소한으로 수집**해야 한다.

- `RateLimitInterceptor`는 **rate limit을 초과했을 때만** IP를 남긴다 — 남용 방지라는 명확한 목적이 있고, 정상 요청에서는 수집하지 않으므로 개인정보보호법 제15조 1항 6호(정당한 이익 처리) 범위에서 방어 가능한 최소 수집.
- `RequestLoggingFilter`에는 **의도적으로 IP를 넣지 않았다.** method+path+status+durationMs+requestId만으로 디버깅에 충분하고, 이 프로젝트는 로그인/계정 시스템이 없는 공개 API라 매 요청마다 IP를 쌓아둘 정당한 이유가 없다.
- 추후 "특정 IP의 반복적인 이상 패턴 탐지" 같은 요구가 생기면, 원본 IP 대신 마지막 옥텟 마스킹이나 단방향 해시를 쓰고 보관 기간을 짧게 두는 방향으로 확장한다. 현재 규모에서는 과한 수집이므로 보류.

## 실무 로깅 사례 검토 (빠진 고려사항)

실무 사례를 조사해서 아래 항목을 추가로 검토했다. 1번만 실제 반영을 권장하고, 나머지는 지금 규모엔 과하거나 해당 없어서 "트리거 조건만 기록"해두는 항목이다.

### 1. 클라이언트 상관관계 ID(X-Request-Id) — 있으면 유용, 필수는 아님 (정정)

지금 설계는 서버가 항상 새 requestId를 발급한다. 실무 표준은 "들어온 요청에 `X-Request-Id` 헤더가 있으면 그대로 쓰고, 없으면 새로 발급"하는 방식이다([X-Request-ID 가이드](https://http.dev/x-request-id), [correlation id 구현 가이드](https://oneuptime.com/blog/post/2026-01-30-request-trace-correlation/view)).

- 반영안(선택): `RequestLoggingFilter`에서 `request.getHeader("X-Request-Id")`가 있으면 그 값을 쓰고, 없으면 새로 발급 + `response.setHeader("X-Request-Id", requestId)`로 응답에 돌려줌.
- **정정**: 처음엔 이걸 `config/WebConfig.java`에 `.exposedHeaders(...)`가 없는 것과 묶어서 "CORS 갭"이라고 표현했는데, 실제 프런트 코드([js/core/api.js](../../football-obs-frontend/js/core/api.js) L65-67)를 확인해보니 부정확했다:
  - 이 이슈는 이미 프런트 개발자가 알고 있었고, `RATE_LIMIT_FALLBACK_MS`(5000ms) fallback으로 이미 방어돼 있음 — 새로 발견한 문제가 아니었음.
  - CORS `exposedHeaders`는 JS(`res.headers.get(...)`)가 *프로그래밍적으로* 읽을 수 있느냐만 제한한다. 브라우저 DevTools Network 탭은 CORS와 무관하게 원본 헤더를 그대로 보여주므로, `X-Request-Id`를 응답에 넣기만 하면 `exposedHeaders`를 안 고쳐도 "개발자가 Network 탭에서 수동으로 보고 Render 로그에서 grep"하는 용도로는 충분함.
  - 이 프로젝트엔 정식 지원 티켓 흐름이 없고 실제 사용자(스트리머)가 Network 탭을 보고 문의하는 경우도 현실적으로 없어서, "필요하다"보다는 "협업 프런트 개발자가 디버깅할 때 정도만 쓸모 있는 저비용 개선"이 정확한 우선순위임. `exposedHeaders` 수정은 프런트 JS가 이 값을 실제로 소비할 계획이 생길 때만 필요.

### 2. MDC 스레드 누수(leak) — 이미 반영됨, 근거만 명시

Tomcat은 요청 처리 스레드를 풀링해서 재사용하므로 `MDC.remove()`를 안 하면 다음 요청이 이전 요청의 requestId를 그대로 물고 로그를 찍는 사고가 난다([MDC 개념/함정 정리](https://medium.com/@pradeepisuru31/understanding-mdc-in-spring-boot-concepts-pitfalls-and-best-practices-f61594ed1efd)). 지금 설계의 `finally { ...; MDC.remove(...); }`가 이미 이걸 막고 있음 — 코드 변경 불필요, 근거만 문서화.

### 3. 로그 인젝션(개행 삽입) — 지금은 안전, 확장 시 주의

지금 로그에 찍는 값(method, path, status, durationMs, fixtureId 등)은 전부 서버가 만들거나 Spring이 타입 변환한 값이라 개행문자를 넣을 수 없다. 하지만 나중에 `User-Agent`, `Referer` 같은 요청 헤더 값을 로그에 그대로 추가하면, 헤더에 실제 개행문자(`\n`)를 넣어 가짜 로그 줄을 위조하는 로그 인젝션이 가능해진다([request logging redaction 가이드](https://medium.com/@AlexanderObregon/request-logging-redaction-in-spring-boot-filters-8e98205d4807)). 지금 반영할 코드는 없고, "헤더/쿼리 값을 로그에 추가할 때는 개행 제거 후 넣는다"는 원칙만 문서화.

### 4. 민감정보 마스킹 — 지금은 해당 없음, 원칙만 기록

API-Football 키(`x-apisports-key`)는 BunnyCDN Edge Script가 CDN 레이어에서 주입하므로 이 백엔드 코드/로그에는 아예 등장하지 않는다. 지금은 마스킹할 대상이 없지만, 나중에 요청/응답 헤더 전체를 로그로 남기는 기능을 추가한다면 `Authorization`, `x-apisports-key` 같은 값은 반드시 마스킹해야 한다는 원칙을 남겨둔다.

### 5. 로그 볼륨 — 지금 규모엔 적정, 트래픽 늘면 threshold 로그로 전환

지금 설계(요청마다 INFO 1줄 + upstream 호출마다 INFO 1줄)는 지금 트래픽(개인 OBS 오버레이, 폴링 15초~1분 간격)에는 적정하다. 트래픽이 늘어 로그 양이 문제가 되면, 매 요청을 다 남기지 않고 "느린 요청만 남기는" 방식으로 전환하는 게 실무에서 흔한 패턴이다 — 예: `durationMs > 1000`일 때만 WARN, 그 외엔 DEBUG로 낮춤. 지금은 적용하지 않고 트리거 조건만 기록.

### 6. plain text vs JSON 구조화 로그 — 지금은 plain text가 맞음

실무에서는 운영 환경에 JSON 구조화 로그(logstash-logback-encoder 등)를 쓰는 게 표준으로 언급된다([구조화 로깅 가이드](https://uptrace.dev/glossary/structured-logging)) — Datadog/Loki 같은 로그 수집기가 필드 단위로 검색 가능해지기 때문. 다만 이 프로젝트는 Render 콘솔에서 사람이 눈으로 grep하는 운영 방식(`[KO_NAME_NEEDED]` 키워드 필터링 흐름 포함)이라 지금 plain text 패턴이 맞는 선택이다. 나중에 별도 로그 수집기를 붙이게 되면 JSON 전환을 재검토.

### 7. 비동기/스케줄러 스레드로 MDC 미전파 — 지금은 해당 없음

지금 코드는 전부 동기 처리(`RestClient` blocking, `@Async`/스케줄러 없음)라서 MDC가 항상 같은 스레드에서 자연스럽게 이어진다. 나중에 백그라운드 폴링 잡이나 `@Async`를 추가하면 MDC가 자동으로 새 스레드로 안 넘어가서 requestId가 끊긴다 — 그때는 `TaskDecorator`로 MDC를 명시적으로 복사해야 함. 지금은 발생하지 않는 문제라 코드 변경 없음, 트리거 조건만 기록.

## 적용 시 체크리스트

- [x] `application.yaml`에 `logging.pattern.console`, `logging.level.root` 추가
- [x] `config/RequestLoggingFilter.java` 신규 생성
- [x] `client/ApiFootballClient.java`의 `fetchRoot` 완료 로그 추가
- [x] `FixtureService`/`KoResolver`의 기존 `[KO_NAME_NEEDED]` 등 INFO 태그 로그는 손대지 않았는지 확인 (변경 없음 확인됨)
- [ ] (선택, 저비용) `RequestLoggingFilter`가 들어온 `X-Request-Id` 헤더를 우선 사용하고, 응답에도 같은 값을 돌려주도록 구현 — DevTools 수동 확인용이라 `exposedHeaders` 수정 없이도 유효함
- [ ] 배포 후 Render 로그에서 requestId 컬럼이 정상 출력되는지 확인
