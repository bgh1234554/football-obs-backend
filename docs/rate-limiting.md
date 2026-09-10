# Rate Limit 정책

- 관련 파일: [`config/RateLimitInterceptor.java`](../src/main/java/com/github/baek/footballobsbackend/config/RateLimitInterceptor.java), [`config/WebConfig.java`](../src/main/java/com/github/baek/footballobsbackend/config/WebConfig.java)
- 프런트 대응 코드: `football-obs-frontend/js/core/api.js` (`readRetryAfterMs`, `normalizeRetryDelayMs`)

## 1. 왜 rate limit이 필요한가 (개념)

이 백엔드는 API-Football 유료 API를 BunnyCDN 경유로 호출한다. API-Football은 요청 횟수만큼 비용/쿼터가 소모되므로, 누군가(악의적이든 오작동이든)가 짧은 시간에 같은 IP로 `/api/fixtures/{id}`를 수백 번 호출하면 그대로 API-Football 요청도 수백 번 나가서 쿼터가 순식간에 소진될 수 있다. Rate limit은 "한 IP가 짧은 시간에 너무 많이 요청하면 429(Too Many Requests)로 잠깐 막는" 장치로, 이런 남용/오작동으로부터 쿼터와 서버 자원을 보호한다.

## 2. 토큰 버킷(Token Bucket) 알고리즘 (개념)

rate limit을 구현하는 방법은 여러 가지가 있는데(고정 윈도우, 슬라이딩 윈도우, 토큰 버킷 등), 이 프로젝트는 **토큰 버킷** 알고리즘을 쓴다(`io.github.bucket4j` 라이브러리).

개념은 이렇다:
- IP마다 "토큰이 담긴 통(버킷)"이 하나씩 있다고 생각한다.
- 통의 최대 용량(capacity)이 있고, 처음엔 가득 차 있다.
- 요청 1건마다 토큰 1개를 소모한다. 토큰이 없으면 요청을 막는다(429).
- 시간이 지나면 일정 속도로 토큰이 다시 채워진다(refill).

고정 윈도우(예: "1분에 최대 20번") 방식과 다른 점은, 토큰 버킷은 "짧은 순간의 몰림(burst)"은 허용하면서도 "평균적으로 낼 수 있는 최대 속도"는 refill 속도로 계속 제한한다는 것이다. 예를 들어 통에 토큰이 15개 차 있으면 순간적으로 15번 연달아 요청해도 통과되지만, 그 뒤로는 refill 속도(이 프로젝트는 4초에 1개)로만 다시 요청할 수 있다.

## 3. `Retry-After` 표준 헤더 (개념)

HTTP 표준(RFC 7231)에 정의된 응답 헤더로, "지금은 안 되니 몇 초/언제 뒤에 다시 시도하라"는 의미다. 값은 두 형식 중 하나다:
- 초 단위 정수 (예: `Retry-After: 4`)
- HTTP-date 형식 (예: `Retry-After: Wed, 21 Oct 2015 07:28:00 GMT`)

429/503 응답과 함께 흔히 쓰이며, 표준 헤더라서 프록시/브라우저/각종 HTTP 클라이언트가 범용적으로 이해한다. 다만 초 단위라서 프런트가 `setTimeout`으로 정확한 대기시간을 계산하기엔 해상도가 거칠다(4.2초를 기다려야 하는데 이 헤더로는 "5초"까지만 표현 가능) — 그래서 이 프로젝트는 표준 헤더와 별개로 `X-Retry-After-Millis`라는 커스텀 헤더도 ms 단위로 같이 내려준다.

## 4. 이 프로젝트의 실제 정책

| 항목 | 값 | 의미 |
| --- | --- | --- |
| 버킷 용량(capacity) | 15 | 한 IP가 순간적으로 최대 15번까지 burst 요청 가능 |
| 충전 속도(refill) | 4초마다 1개 | 토큰이 0개인 상태라면 다음 1개가 채워지기까지 최대 4초 |
| 적용 범위 | `/api/**` | `/actuator/health` 등은 적용 안 됨 (`WebConfig.addInterceptors`) |
| 식별 키 | 요청자 IP (`resolveClientIp()` — `X-Forwarded-For` 첫 값, 없으면 `getRemoteAddr()` 폴백) | IP별로 별도 버킷. 왜 `getRemoteAddr()`를 직접 안 쓰는지는 [client-ip-resolution.md](client-ip-resolution.md) 참고 |

흐름:

```
요청 진입
  -> WebConfig가 등록한 RateLimitInterceptor.preHandle 실행
  -> 요청 IP로 버킷 조회 (없으면 새로 생성, 토큰 15개 가득 채워서 시작)
  -> 토큰 1개 소모 시도 (tryConsumeAndReturnRemaining)
       성공 -> 응답에 X-RateLimit-Limit/Remaining 헤더만 얹고 통과 (컨트롤러로 진행)
       실패 -> 429 응답 직접 작성 + 헤더 세팅 + [LIMIT_EXCEEDED] 로그, 컨트롤러 진입 안 함
```

## 5. 버킷을 어디에 저장하나 (Caffeine)

IP별 버킷을 `ConcurrentHashMap`이 아니라 Caffeine 캐시에 저장한다. `ConcurrentHashMap`은 넣은 키를 알아서 안 지워주기 때문에, 한 번이라도 요청한 IP가 계속 쌓여서 시간이 지날수록 메모리를 갉아먹는다(메모리 누수). Caffeine은 TTL 기반으로 자동 정리되는 캐시라서 이 문제를 해결한다.

- `expireAfterAccess(10분)`: 마지막 요청 이후 10분간 조회가 없으면 그 IP의 버킷을 지운다. 폴링 간격이 1분 이내인 이 프로젝트에서 10분 무응답이면 사실상 떠난 사용자로 봐도 되고, 다시 돌아오면 새 버킷(토큰 15개 가득)을 받으니 사용자 입장에서 손해는 없다.
- `maximumSize(10,000)`: TTL이 정상 동작하면 거의 안 닿는 안전장치 — 트래픽이 갑자기 몰려도 메모리 사용량 상한을 걸어둔다.

## 6. 초과했을 때(429) 응답

```http
HTTP/1.1 429 Too Many Requests
Retry-After: 4
X-Retry-After-Millis: 3821
X-RateLimit-Limit: 15
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

정상 통과한 요청에도 `X-RateLimit-Limit`/`X-RateLimit-Remaining` 헤더는 항상 붙는다 — 몇 개 남았는지 디버깅/모니터링할 때 유용하다.

## 7. 로그

초과했을 때만 INFO로 남긴다 (`[LIMIT_EXCEEDED] Rate limit exceeded for IP: {}, retryAfterMs={}`). 정상 요청은 이 인터셉터 단계에서 로그를 안 남기고, 대신 [logging.md](logging.md)에서 다룬 `RequestLoggingFilter`가 모든 `/api/**` 요청(429로 막힌 것 포함)을 최종 status 기준으로 1줄씩 남긴다 — 429가 얼마나 자주 나는지는 그 로그에서 `status=429`로 집계 가능.

## 8. 프런트 대응

`football-obs-frontend/js/core/api.js`의 `apiFetch`가 429를 받으면 `X-Retry-After-Millis` → `Retry-After` → 5초 하드코딩 fallback 순서로 대기시간을 읽어 자동 재시도(최대 2회)한다. 헤더를 못 읽는 경우까지 이미 방어돼 있음(`RATE_LIMIT_FALLBACK_MS`).

## 9. 클라이언트 IP 식별

IP별로 버킷을 나누는 이 인터셉터의 전제(=`getRemoteAddr()`가 실제 방문자 IP다)가 Render 배포 환경에서는 깨질 수 있어서 별도로 조사·수정했다. 배경, Render 공식 근거, 이 프로젝트에서 실측한 데이터, 적용한 수정 코드는 [client-ip-resolution.md](client-ip-resolution.md)에 정리했다.
