# Rate Limit 정책

- 관련 파일: [`config/RateLimitInterceptor.java`](../src/main/java/com/github/baek/footballobsbackend/config/RateLimitInterceptor.java), [`config/UpstreamRateLimiter.java`](../src/main/java/com/github/baek/footballobsbackend/config/UpstreamRateLimiter.java), [`config/WebConfig.java`](../src/main/java/com/github/baek/footballobsbackend/config/WebConfig.java)
- 구조·코드 설명: [두 단계 요청 제한의 구조와 코드 흐름](rate-limiting-architecture.md)
- 프런트 대응 코드: `football-obs-frontend/js/core/api.js` (`readRetryAfterMs`, `normalizeRetryDelayMs`)

## 1. 왜 rate limit이 필요한가 (개념)

이 백엔드는 API-Football 유료 API를 BunnyCDN 경유로 호출한다. 외부 API에는 사용량·호출 속도 한도가 있고, 서버도 요청을 받아 JSON을 복사·가공·전송할 때 자원을 사용한다. Rate limit은 "짧은 시간에 너무 많은 요청이 들어오면 429(Too Many Requests)로 잠깐 막는" 장치로, 남용이나 오작동으로부터 쿼터와 서버 자원을 보호한다.

다만 백엔드 요청 수와 실제 외부 호출 수는 같지 않다. 캐시된 패널을 다시 보는 요청은 외부 API를 호출하지 않아도 서버에서는 처리한다. 그래서 서버 입구와 외부 호출의 두 단계로 나눈다. 서버 입구는 IP별 요청을 세고, 외부 단계는 실제 HTTP 시도를 IP별·전체 버킷으로 함께 제한한다.

## 2. 토큰 버킷(Token Bucket) 알고리즘 (개념)

rate limit을 구현하는 방법은 여러 가지가 있는데(고정 윈도우, 슬라이딩 윈도우, 토큰 버킷 등), 이 프로젝트는 **토큰 버킷** 알고리즘을 쓴다(`io.github.bucket4j` 라이브러리).

개념은 이렇다:

- 제한 대상마다 "토큰이 담긴 통(버킷)"이 하나씩 있다고 생각한다. 이 프로젝트는 IP별 서버 버킷, IP별 외부 버킷, 전체 외부 버킷이 있다.
- 통의 최대 용량(capacity)이 있고, 처음엔 가득 차 있다.
- 요청 1건마다 토큰 1개를 소모한다. 토큰이 없으면 요청을 막는다(429).
- 시간이 지나면 일정 속도로 토큰이 다시 채워진다(refill).

고정 윈도우처럼 특정 시간 구간마다 횟수를 새로 세는 방식과 달리, 토큰 버킷은 "짧은 순간의 몰림(burst)"을 허용하면서 지속 가능한 평균 속도를 refill로 제한한다. 가득 찬 버킷은 capacity만큼 연속 요청을 허용하고, 소진된 뒤에는 설정된 속도로 회복된 토큰만 다시 사용할 수 있다.

## 3. `Retry-After` 표준 헤더 (개념)

HTTP 표준(RFC 7231)에 정의된 응답 헤더로, "지금은 안 되니 몇 초/언제 뒤에 다시 시도하라"는 의미다. 값은 두 형식 중 하나다:

- 초 단위 정수 (예: `Retry-After: 4`)
- HTTP-date 형식 (예: `Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`)

429/503 응답과 함께 흔히 쓰이며, 표준 헤더라서 프록시/브라우저/각종 HTTP 클라이언트가 범용적으로 이해한다. 다만 초 단위라서 프런트가 `setTimeout`으로 정확한 대기시간을 계산하기엔 해상도가 거칠다(4.2초를 기다려야 하는데 이 헤더로는 "5초"까지만 표현 가능) — 그래서 이 프로젝트는 표준 헤더와 별개로 `X-Retry-After-Millis`라는 커스텀 헤더도 ms 단위로 같이 내려준다.

## 4. 이 프로젝트의 실제 정책

| 단계 | 적용 대상 |
| --- | --- |
| 서버 입구 | `/api/**`의 요청자 IP별 요청 |
| 외부 호출 | 실제 외부 HTTP 시도를 실행한 IP |
| 외부 호출 전체 | 모든 IP의 실제 외부 HTTP 시도 합계 |

외부 호출의 사용자별·전체 제한은 하나의 보호 단계입니다. 두 버킷에 모두 여유가 있을 때만 각각 1건을 차감합니다. 한쪽에서 차단되면 다른 쪽에서도 차감하지 않습니다. 서버 입구에서 이미 처리한 요청의 서버 한도는 반환하지 않습니다.

토큰 버킷이므로 처음에는 용량만큼 연속 호출할 수 있고, 이후 회복 속도에 따라 다시 호출할 수 있습니다. 고정된 분당 호출 횟수나 일일 사용량 제한은 아닙니다. `/actuator/health`는 이 제한을 적용하지 않습니다.

```text
요청 → 서버 보호 제한 → Controller / Service → CdnResponseCache
                                              ├─ 적중 → 복사·가공·응답
                                              └─ 미적중 → 같은 URI의 일반 동시 요청 합치기
                                                          → 사용자별·전체 외부 한도 함께 확인
                                                          → 외부 HTTP 시도 → 캐시 저장·응답
```

두 단계의 적용 범위는 다음과 같다.

| 항목 | 적용 방식 |
| --- | --- |
| 서버 적용 범위 | `/api/**` (단, `/actuator/health`는 제외) (`WebConfig.addInterceptors`) |
| 외부 차감 위치 | `ApiFootballClient.fetchRoot`의 캐시 로더 안, HTTP 시도 직전 |
| IP 식별 키 | `ClientIpResolver.resolve()` — Render에서는 유효한 `CF-Connecting-IP`, 그 외에는 `getRemoteAddr()` |

IP별·전체 외부 버킷의 용량과 회복 속도가 같으면 전체 버킷이 먼저 또는 동시에 소진돼 IP별 버킷의 추가 제한 효과가 없다. 전체 버킷은 여러 사용자가 공유할 예산으로 별도 설정하며, 실제 운영 한도와 회복 속도는 공개 문서에 기재하지 않는다.

### 무엇을 한 번으로 세는가

- 서버 입구에서는 백엔드 HTTP 요청 하나를 1건으로 셉니다.
- 외부 호출에서는 `ApiFootballClient`가 BunnyCDN으로 HTTP 요청을 시도하는 횟수를 셉니다. 한 경기 조회가 경기·부상·선수 등 여러 외부 경로를 조회하면 각 시도를 따로 셉니다.
- 백엔드 메모리 캐시 적중과 같은 URI의 일반 동시 요청에 합쳐진 대기자는 외부 한도를 차감하지 않습니다.
- 수동 강제 새로고침은 기존처럼 캐시 읽기를 우회하며 실제 시도마다 차감합니다. 한도가 없으면 요청을 보내지 않고 기존 캐시도 유지합니다.
- 네트워크 실패·외부 오류가 나도 이미 시도한 호출은 차감합니다. 외부 제한 예외와 실패 응답은 캐시하지 않습니다.
- BunnyCDN 내부에서 캐시로 응답했는지는 이 제한의 판단 대상이 아닙니다. 이 수치는 API-Football에서 실제 차감한 쿼터와 같다고 볼 수 없습니다.

캐시는 원본 JSON 단위입니다. 캐시 적중에도 JSON 복사·DTO 조립·직렬화·전송 비용이 있어 서버 보호 제한을 유지합니다. 기존 캐시 TTL과 Age 처리, URI별 동시 요청 병합은 바꾸지 않습니다.

### 설정

운영 설정은 배포 환경에서 주입한다. 필요한 설정이 없거나 값이 유효하지 않으면 시작 시 오류가 나도록 하며, Java에 별도 기본값을 중복하지 않는다. 환경변수 이름·값과 실제 운영 한도는 이 문서에 기록하지 않는다.

## 5. 버킷을 어디에 저장하나 (Caffeine)

IP별 버킷을 `ConcurrentHashMap`이 아니라 Caffeine 캐시에 저장한다. `ConcurrentHashMap`은 넣은 키를 알아서 안 지워주기 때문에, 한 번이라도 요청한 IP가 계속 쌓여서 시간이 지날수록 메모리를 갉아먹는다(메모리 누수). Caffeine은 TTL 기반으로 자동 정리되는 캐시라서 이 문제를 해결한다.

- `expireAfterAccess(10분)`: 마지막 요청 이후 10분간 조회가 없으면 그 IP의 버킷을 지운다. 폴링 간격이 1분 이내인 이 프로젝트에서 10분 무응답이면 사실상 떠난 사용자로 봐도 되고, 다시 돌아오면 해당 단계의 용량만큼 채운 새 버킷을 받으니 사용자 입장에서 손해는 없다.
- `maximumSize(10,000)`: TTL이 정상 동작하면 거의 안 닿는 안전장치 — 트래픽이 갑자기 몰려도 메모리 사용량 상한을 걸어둔다.

서버 입구 버킷과 IP별 외부 버킷은 각각 별도의 Caffeine 저장소를 사용한다. 전체 외부 버킷은 IP별 캐시 밖에 두므로 IP 항목이 제거돼도 전체 예산까지 초기화되지는 않는다. Caffeine 항목 수 제한에 따른 제거도 같은 구분을 따른다.

이 버킷 저장소와 `CdnResponseCache`는 목적이 다르다. 전자는 요청자의 남은 허용량을 저장하고, 후자는 외부에서 받은 원본 JSON을 저장한다.

## 6. 초과했을 때(429) 응답

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 4
X-Retry-After-Millis: 3821
X-RateLimit-Scope: upstream-global
X-RateLimit-Limit: <해당 버킷의 설정된 용량>
X-RateLimit-Remaining: 0
Cache-Control: no-store
Content-Type: application/json;charset=UTF-8
```
```json
{
  "code": "RATE_LIMIT_EXCEEDED",
  "message": "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.",
  "status": 429,
  "path": "/api/fixtures/123",
  "timestamp": "2026-09-10T05:23:10.123Z"
}
```

`Cache-Control: no-store`를 명시하는 이유는, 중간에 캐시(브라우저/CDN)가 이 429 응답을 붙잡고 있다가 나중에 토큰이 다시 찼는데도 429를 계속 돌려주는 사고를 막기 위함이다.

정상 통과한 요청에도 `X-RateLimit-Limit`/`X-RateLimit-Remaining` 헤더는 항상 붙는다 — 몇 개 남았는지 디버깅/모니터링할 때 유용하다. 정상 응답은 서버 입구 버킷의 값이고, 외부 단계에서 거절되면 해당 외부 버킷의 값으로 덮어쓴다.

| 헤더 | 의미 |
| --- | --- |
| `X-RateLimit-Scope` | `server`, `upstream-ip`, `upstream-global` 중 적용된 제한 |
| `X-RateLimit-Limit` | 해당 버킷 용량 |
| `X-RateLimit-Remaining` | 남은 토큰, 외부 제한 거절 시 0 |
| `Retry-After` | 거절 시 재시도까지 올림한 초 |
| `X-Retry-After-Millis` | 거절 시 재시도까지 밀리초 |
| `Cache-Control` | 거절 응답은 `no-store` |

CORS로 위 헤더를 노출하며 프런트의 기존 자동 재시도 경로를 유지한다. 실제로 재시도할 때는 다른 요청이 토큰을 먼저 사용했을 수 있다.

## 7. 로그

초과했을 때만 INFO로 남긴다 (`[LIMIT_EXCEEDED] scope=server ip={} retryAfterMs={}` 또는 `scope=upstream-ip` / `scope=upstream-global`). 외부 제한 로그는 `GlobalExceptionAdvice`에서 남긴다. 정상 요청은 이 인터셉터 단계에서 로그를 안 남기고, 대신 [logging.md](logging.md)에서 다룬 `RequestLoggingFilter`가 모든 `/api/**` 요청(429로 막힌 것 포함)을 최종 status 기준으로 1줄씩 남긴다 — 429가 얼마나 자주 나는지는 그 로그에서 `status=429`로 집계 가능.


### 전체 외부 버킷 소진율 로그

`UpstreamRateLimiter`는 전체 외부 버킷의 소진율이 80·85·90·95% 구간에 도달하면 WARN, 남은 토큰이 0인 100%에서는 ERROR를 기록한다. `[UPSTREAM_BUDGET_USAGE]`의 `thresholdPercent`, `usedPercent`, `capacity`, `remaining`으로 구간과 실제 상태를 구분한다.

같은 구간이나 더 낮은 구간에서는 반복 기록하지 않는다. 소진율이 60% 이하로 회복된 것을 다음 외부 호출 시도에서 확인하면 기록 단계를 초기화한다. 작은 버킷에서는 토큰 하나가 여러 구간을 넘을 수 있어, 그때는 가장 높은 도달 구간 한 번만 기록한다. 토큰 한 개가 여러 경고 구간에 해당하는 작은 버킷은 중간 WARN 구간을 건너뛰고 100% ERROR로 넘어갈 수 있다. 토큰이 회복될 때마다 별도 타이머를 실행하거나 캐시 조회를 위해 추가 외부 호출을 하지는 않는다.

이는 서버 요청 수나 API-Football 일일 쿼터의 80%가 아니라, 현재 JVM 안의 전체 외부 토큰 버킷 소진율이다. 100% ERROR는 버킷이 완전히 소진됐다는 신호이며 이미 허용한 HTTP 호출의 실패나 서버 장애를 의미하지 않는다. 기존 429 거절 로그는 별도로 유지한다.

## 8. 프런트 대응

`football-obs-frontend/js/core/api.js`의 `apiFetch`가 429를 받으면 `X-Retry-After-Millis` → `Retry-After` → 5초 하드코딩 fallback 순서로 대기시간을 읽어 자동 재시도(최대 2회)한다. 헤더를 못 읽는 경우까지 이미 방어돼 있음(`RATE_LIMIT_FALLBACK_MS`).

## 9. 클라이언트 IP 식별

IP별로 버킷을 나누는 이 인터셉터의 전제(=`getRemoteAddr()`가 실제 방문자 IP다)가 Render 배포 환경에서는 깨질 수 있어서 별도로 조사·수정했다. 배경, Render 공식 근거, 이 프로젝트에서 실측한 데이터, 적용한 수정 코드는 [client-ip-resolution.md](client-ip-resolution.md)에 정리했다.

두 단계 모두 공통 `ClientIpResolver.resolve()`를 사용한다. HTTP 요청 문맥이 없는 외부 호출에는 `internal` 키와 전체 버킷을 적용한다.

## 10. 인스턴스 범위와 검증

버킷은 JVM 메모리에 있으므로 재시작하면 초기화되고 여러 인스턴스는 각각 별도의 전체 한도를 갖는다. 여러 서버가 같은 API 키를 공유한다면 제한 상태도 공용 저장소나 CDN의 실제 API 호출 지점에서 공유해야 한다. 이 제한은 일일 API 쿼터를 직접 추적하는 기능은 아니다.

- `config/RateLimitInterceptor.java`: 서버 입구 제한
- `config/UpstreamRateLimiter.java`: 사용자별·전체 외부 호출의 원자적 승인
- `client/ApiFootballClient.java`: 캐시 미적중 로더 안에서 외부 호출 승인
- `config/ClientIpResolver.java`, `config/RateLimitHeaders.java`: IP·응답 계약 공유
- `error/GlobalExceptionAdvice.java`: 외부 제한의 429 응답

`RateLimitInterceptorTest`, `UpstreamRateLimiterTest`, `ApiFootballClientCacheTest`, `CdnResponseCacheTest`, `FixtureControllerTest`에서 서버 한도, 기존 회복 속도, 캐시 적중·강제 새로고침·외부 실패, 동시 승인과 차감, CORS·재시도 응답을 검증합니다.
