# 클라이언트 IP 식별

`RateLimitInterceptor`와 `UpstreamRateLimiter`는 공통 유틸 `ClientIpResolver.resolve()`를 사용한다. 이 메서드는 Tomcat이 검증한 `request.getRemoteAddr()`를 반환하며, 원본 `X-Forwarded-For`를 직접 읽지 않는다.

## 프록시 뒤에서 실제 방문자 IP가 필요한 이유

전달 헤더 처리를 적용하기 전의 `request.getRemoteAddr()`는 애플리케이션에 직접 연결한 상대의 주소다. 로컬에서 직접 호출하면 방문자 주소이지만, Render처럼 로드밸런서나 리버스 프록시가 앞에 있으면 프록시 주소가 될 수 있다.

이 주소를 그대로 제한 버킷의 키로 사용하면 서로 다른 방문자가 같은 버킷을 공유하거나, 같은 방문자의 요청이 여러 프록시 주소로 나뉘어 서로 다른 버킷에 들어갈 수 있다. 따라서 실제 방문자를 구분하되, 클라이언트가 임의로 넣은 전달 헤더로 버킷을 바꾸지 못하게 해야 한다.

## 전달 헤더와 신뢰 프록시

`server.forward-headers-strategy: native`로 Tomcat의 `RemoteIpValve`를 사용한다. 신뢰 프록시에서 온 요청에 한해 `X-Forwarded-For`를 오른쪽부터 검사하고, 신뢰하지 않는 첫 주소를 방문자 IP로 사용한다. 앞쪽에 클라이언트가 넣은 가짜 값이 있어도 그 값까지 거슬러 올라가지 않는다.

Spring Boot 설정은 [공식 문서](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)를 따른다.

`TRUSTED_PROXY_REGEX`는 실제 배포 환경의 프록시 주소에만 일치하는 Java 정규식으로 설정한다. 현재 `application.yaml`에서는 이 환경변수를 필수로 요구한다. 프록시를 신뢰하지 않을 때는 `(?!)`를 명시한다. 이 정규식은 어떤 주소에도 일치하지 않는다. 빈 값은 Tomcat에서 전체 프록시 신뢰를 뜻하므로 애플리케이션 시작 시 거부한다.

Render에서는 **서비스 → Environment**에서 등록한 뒤 재배포한다. 로컬에서는 프로젝트 루트의 `.env`에 설정한다.

예를 들어 직접 연결하는 프록시가 `10.20.0.5`이고 앞단 프록시가 `10.20.0.6`인 환경이라면 다음처럼 지정한다.

```dotenv
TRUSTED_PROXY_REGEX=10[.]20[.]0[.]5|10[.]20[.]0[.]6
```

이 주소는 설명용 예시다. 실제 연결 경로와 신뢰할 프록시 주소를 확인해 설정해야 한다. 프록시 설정이 없으면 연결 상대 주소를 기준으로 제한하므로, 프록시 뒤의 여러 방문자가 같은 버킷을 공유할 수 있다.

## Render 배포 확인

기존 문서는 Render가 항상 첫 IP를 실제 방문자 IP로 덮어쓴다고 해석했지만, [Render 게시판](https://feedback.render.com/features/p/send-the-correct-x-forwarded-for)에는 과거 운영자 답변과 이후 헤더 추가 동작을 보고한 댓글이 함께 있다. 이를 현재 배포의 위조 방지 보장으로 사용하지 않는다.

Render에서 실제 연결 상대 주소와 전달 헤더 체인을 확인한 뒤 신뢰 프록시 정규식을 설정한다. 정상 요청과 임의의 `X-Forwarded-For`를 넣은 요청에서 두 제한기가 같은 방문자 버킷을 사용하는지 확인한다. 맨 앞 또는 맨 뒤 값을 무조건 선택하거나 모든 주소를 신뢰하는 설정은 사용하지 않는다.

### 이전 문제 해결 기록

2026-09-10에 기록한 배포 확인에서는 같은 발신지에서 1초 간격으로 다섯 번 요청했을 때 `X-RateLimit-Remaining`이 다음과 같이 관찰됐다.

```text
14, 13, 14, 14, 14
```

당시 제한은 용량 15개, 4초마다 토큰 1개 회복이었다. 같은 버킷을 사용했다면 설명하기 어려운 회복이 나타나 IP 식별 경로를 조사했다. 당시 Render 대시보드에서는 Free 플랜의 단일 인스턴스를 확인했고, 응답의 `CF-RAY` 콜로 코드는 `KIX → NRT → KIX → NRT → KIX`로 바뀌었다. 프록시 경로 변화에 따라 다른 버킷을 사용했을 가능성을 원인으로 추정했다.

이 기록은 당시의 관찰과 추정이다. `CF-RAY`의 거점 변화만으로 애플리케이션에 연결한 프록시 IP가 바뀌었다고 확정할 수는 없다. 현재 배포의 인스턴스 수와 프록시 경로도 다시 확인해야 한다. 현재는 두 단계 제한을 사용하므로 위 수치나 감소 패턴을 그대로 정상 동작 기준으로 사용하지 않는다.

당시에는 전달 헤더의 첫 값을 직접 사용하는 방식으로 대응했다. 현재는 헤더 위조 가능성을 고려해 신뢰 프록시를 설정하고 Tomcat이 주소를 해석하도록 변경했다. 프록시 뒤에서 방문자 주소를 식별해야 한다는 문제 자체는 그대로다.

## 문제 해결

### 전달 헤더 비교 진단

Render의 **서비스 → Environment**에 다음 값을 등록하고, 진단 코드가 포함된 버전을 배포한다. IP 식별 설정인 `TRUSTED_PROXY_REGEX`는 이 검사 중에 변경하지 않는다.

```dotenv
CLIENT_IP_DIAGNOSTICS_LOG_LEVEL=DEBUG
```

이 설정을 켜면 `/api/__ip_check__` 요청에만 `[CLIENT_IP_DIAGNOSTIC]` 로그가 남는다. 일반 API 요청에는 진단 로그를 남기지 않는다. Render의 **Logs**에서 이 태그로 필터링한 뒤 PowerShell에서 다음 두 요청을 보낸다.

```powershell
$base = 'https://football-obs-backend.onrender.com'
$checkId = [Guid]::NewGuid().ToString('N')
curl.exe --silent --show-error --max-time 60 --output NUL `
    "$base/api/__ip_check__?case=normal-$checkId"
curl.exe --silent --show-error --max-time 60 --output NUL `
    --header 'X-Forwarded-For: 198.51.100.99' `
    "$base/api/__ip_check__?case=spoof-$checkId"
```

로그의 `case`로 두 요청을 짝지어 `xForwardedFor`, `cfConnectingIp`, `trueClientIp`, `remoteAddr`, `selectedIp`를 비교한다. 현재 `TRUSTED_PROXY_REGEX=(?!)`에서는 Tomcat이 전달 헤더로 주소를 변환하지 않으므로 `remoteAddr`와 `selectedIp`는 연결 상대 주소다. 다른 신뢰 프록시 설정에서는 Tomcat이 주소와 전달 헤더를 먼저 변경할 수 있어, 이 로그를 원본 소켓·헤더 기록으로 해석하면 안 된다.

위조 요청의 `xForwardedFor` 첫 값에 `198.51.100.99`가 남아 있다면 첫 값을 그대로 선택하는 기존 방식은 가짜 IP를 버킷 키로 사용할 수 있다. 가짜 값이 제거됐다면 이번 요청 경로에서 제거된 것을 확인한 것이며, 선택할 주소가 실제 방문자 IP인지도 확인한다. `CF-Connecting-IP` 같은 다른 헤더도 한 번의 관찰만으로 무조건 신뢰하도록 바꾸지 않는다.

이 경로는 진단 전용 응답 API가 아니라 IP 식별 로그를 남기기 위한 요청 경로다. 현재 없는 경로에 대한 공통 예외 처리로 500이 반환될 수 있으며, 검사 결과는 응답 상태나 잔여량이 아닌 진단 로그로 판단한다. 외부 축구 API는 호출하지 않는다.

확인이 끝나면 `CLIENT_IP_DIAGNOSTICS_LOG_LEVEL=INFO`로 바꾸고 적용한다. 환경변수 변경은 Render에서 재시작·재배포가 필요할 수 있지만, 진단 코드를 삭제하는 별도 커밋은 필요하지 않다. 로그에는 IP 주소가 포함되므로 공유할 때는 실제 주소를 가리되, 같은 주소가 어디에 반복되는지와 가짜 값의 위치는 구분 가능하게 남긴다.

### 증상별 확인

| 증상 | 확인할 내용 |
| --- | --- |
| 시작 시 `TRUSTED_PROXY_REGEX`를 해석할 수 없다는 오류 | Render 환경변수 또는 로컬 `.env`에 값이 등록됐는지 확인한다. 프록시를 신뢰하지 않을 때도 `(?!)`를 명시한다. |
| 시작 시 빈 정규식 또는 정규식 문법 오류 | 빈 값을 제거하고 실제 프록시 주소에 맞는 Java 정규식을 지정한다. IP 목록을 단순히 쉼표로 나누지 말고 정규식의 `|`로 연결한다. |
| 여러 방문자가 같은 IP로 식별됨 | `(?!)`를 사용 중인지, 신뢰 정규식이 실제 연결 프록시 주소와 일치하는지 확인한다. 체인 중 신뢰하지 않은 프록시가 있으면 그 주소에서 탐색이 멈춘다. |
| 같은 방문자의 잔여량이 예상과 다르게 바뀜 | 먼저 `X-RateLimit-Scope`가 같은지 확인한다. 이어 토큰 회복, 동시 요청, 재배포·재시작, 여러 앱 인스턴스, 식별된 IP 변화를 확인한다. |
| 가짜 `X-Forwarded-For`를 넣었더니 방문자 IP가 달라짐 | 신뢰 범위가 너무 넓지 않은지, 요청 경로의 프록시가 실제 연결 주소를 헤더에 올바르게 기록하는지 확인한다. |

확인할 때는 정상 요청과 가짜 헤더를 넣은 요청의 최종 식별 IP를 비교한다. 응답 헤더에서는 `X-RateLimit-Scope`, `X-RateLimit-Remaining`, `Retry-After`, `X-Retry-After-Millis`를 함께 본다. 서버 제한은 모든 API 요청을 세고 외부 제한은 실제 외부 호출만 세므로, 서로 다른 단계의 잔여량을 같은 버킷의 값으로 비교하지 않는다. 자세한 차감 규칙은 [rate-limiting.md](rate-limiting.md)를 참고한다.

정상 요청과 위조 헤더 요청이 같은 버킷을 사용하는지뿐 아니라, 서로 다른 실제 방문자는 각각의 버킷으로 구분되는지도 확인한다. 전체 외부 버킷은 모든 방문자가 공유하므로 서로 다른 방문자가 같은 `upstream-global` 제한을 받는 것은 정상이다.

## 다른 곳에서도 IP가 필요해지면

IP 기반 통계나 차단 같은 기능에서도 `ClientIpResolver.resolve()`를 재사용한다. 각 기능이 원본 전달 헤더를 따로 해석하면 서버 입구와 외부 호출 제한이 서로 다른 IP를 사용할 수 있다.

HTTP 요청 문맥이 없는 외부 호출은 `UpstreamRateLimiter`에서 `internal` 키를 사용한다. 독립 실행 CSV 업데이터는 자체 호출 간격을 유지하면서 별도 컨텍스트에서 외부 제한을 완화한다. 이는 방문자 IP 식별과는 별도의 실행 경로다.
