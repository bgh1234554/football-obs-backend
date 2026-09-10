# 클라이언트 IP 식별 (Render/Cloudflare 뒤에서)

- 상태: 적용됨 (2026-09-10)
- 관련 파일: [`config/RateLimitInterceptor.java`](../src/main/java/com/github/baek/footballobsbackend/config/RateLimitInterceptor.java) (`resolveClientIp`)
- 이 문서는 원래 [rate-limiting.md](rate-limiting.md)의 9번 섹션이었으나, IP 식별 자체는 rate limit 전용 주제가 아니라(추후 다른 기능에서도 재사용될 수 있음) 별도 문서로 분리함.

## 문제: `request.getRemoteAddr()`는 Render 뒤에서 실제 방문자 IP가 아니다

`request.getRemoteAddr()`는 요청이 애플리케이션 서버에 "곧바로" 도착할 때는 실제 방문자 IP를 정확히 반환한다. 하지만 Render처럼 앱 앞에 로드밸런서/리버스 프록시(+ Cloudflare)가 있는 PaaS 환경에서는 이 값이 실제 방문자가 아니라 **프록시가 오리진(Render 컨테이너)에 연결할 때 쓴 IP**를 반환한다.

Spring Boot가 실제 방문자 IP를 자동으로 풀어서 쓰려면 `server.forward-headers-strategy: native`(Tomcat의 `RemoteIpValve`) 또는 `framework`(Spring의 `ForwardedHeaderFilter`) 설정이 필요한데, 이 프로젝트는 그 설정 없이 `getRemoteAddr()`를 직접 써왔다. 이 값을 IP별 기능(rate limit 등)의 키로 쓰면, 방문자별로 정확히 구분되지 않는다.

## 근거

### 1. Render 공식 확인 — X-Forwarded-For는 신뢰 가능

Render 자체 피드백 게시판([Send the correct X_FORWARDED_FOR](https://feedback.render.com/features/p/send-the-correct-xforwardedfor))에 "클라이언트가 `X-Forwarded-For`를 직접 위조하면 어떡하냐"는 우려가 올라왔고, Render 엔지니어가 직접 답변했다:

> "we set the first IP in the list to the real client IP"

즉 클라이언트가 이 헤더에 가짜 값을 미리 넣어 보내도, Render는 리스트의 **첫 번째 자리를 항상 자신이 관측한 실제 클라이언트 IP로 덮어써서 세팅**한다(Mozilla HTTP 헤더 표준 — "real/client IP address should be first" — 을 따른다고 설명). 이슈 상태는 "complete". `render.com/docs` 공식 레퍼런스 페이지엔 이 내용이 명문화돼 있지 않지만, Render 엔지니어 본인의 공식 답변이라 신뢰할 만한 근거로 판단.

### 2. Render 커뮤니티 실사례

Go 프레임워크의 `c.ClientIP()`가 Render에서 항상 `127.0.0.1`(루프백)을 반환한다는 보고가 있다([스레드](https://render.discourse.group/t/c-clientip-always-returns-127-0-0-1/3086)) — 언어/프레임워크만 다를 뿐 `request.getRemoteAddr()`가 정확히 같은 증상을 보일 수 있음을 뒷받침.

### 3. 이 프로젝트에서 직접 실측한 데이터 (2026-09-10)

실제 배포(Render Free 플랜)를 상대로 1초 간격 5연속 요청을 보내 `X-RateLimit-Remaining`을 관찰:

```
14, 13, 14, 14, 14   (같은 발신지에서 1초 간격으로 보낸 5번의 요청)
```

정상(단일 안정된 버킷을 계속 소모)이라면 `14,13,12,11,10`처럼 단조 감소해야 하는데 13에서 14로 다시 올라간 지점이 있다. 리필 정책(4초에 토큰 1개)상 1초 만에 토큰이 다시 차는 건 불가능하므로, **매 요청이 서로 다른 버킷(=서로 다른 IP)으로 잡히고 있다는 뜻**이다.

가능한 원인을 하나씩 배제:
- **여러 앱 인스턴스가 각자 메모리를 따로 갖고 있어서 생긴 문제 — 배제됨.** Render 대시보드 확인 결과 이 서비스는 Free 플랜이고, Free 플랜은 Scaling(다중 인스턴스) 기능 자체가 없다(왼쪽 메뉴에 Scaling 탭이 아예 안 보임 — 유료 플랜 전용 기능). 인스턴스는 항상 1개로 고정.
- **원격 IP 자체가 매 요청 다르게 잡히는 문제 — 유력.** 같은 5번의 요청에서 `CF-RAY` 헤더의 콜로 코드가 `-KIX`(오사카) → `-NRT`(도쿄) → `-KIX` → `-NRT` → `-KIX`로 매번 다른 Cloudflare 엣지 거점을 탔다. Cloudflare 엣지 거점이 바뀔 때마다 오리진에 연결하는 IP도 달라질 수 있어서, 같은 발신지의 요청인데도 `getRemoteAddr()` 기준으로는 매번 "처음 보는 IP"로 잡힌 것으로 보인다.

Render 공식 답변 + 커뮤니티 사례 + 이 프로젝트 자체 실측 데이터, 세 겹으로 뒷받침되는 결론이라 수정을 적용함.

## 적용한 수정

```java
/**
 * 실제 방문자 IP를 판별한다.
 *
 * Render(+ 앞단 Cloudflare) 뒤에서 request.getRemoteAddr()는 실제 방문자 IP가 아니라
 * 프록시가 오리진(Render 컨테이너)에 연결할 때 쓴 IP를 반환한다 — 근거는 위 섹션 참고.
 *
 * Render는 X-Forwarded-For 리스트의 첫 번째 값을 항상 실제 클라이언트 IP로 세팅한다.
 * 그래서 이 헤더가 있으면 콤마로 나눈 첫 값을 쓰고, 없으면(로컬 실행 등 프록시를 안 거치는
 * 경우) 기존 getRemoteAddr()로 폴백한다.
 */
private String resolveClientIp(HttpServletRequest request) {
    String xff = request.getHeader("X-Forwarded-For");
    if (xff != null && !xff.isBlank()) {
        return xff.split(",")[0].trim();
    }
    return request.getRemoteAddr();
}
```

`RateLimitInterceptor.preHandle`이 `request.getRemoteAddr()` 대신 이 메서드를 호출하도록 변경했다.

## 다른 곳에서도 IP가 필요해지면

지금은 `RateLimitInterceptor`만 IP별 식별이 필요하다. 나중에 다른 기능(예: IP 기반 통계, 차단 등)이 추가되면 `request.getRemoteAddr()`를 직접 쓰지 말고 이 `resolveClientIp` 로직을 재사용해야 한다 — 필요하면 공통 유틸로 추출.
