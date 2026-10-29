# 클라이언트 IP 식별

`RateLimitInterceptor`와 `UpstreamRateLimiter`는 Spring이 주입한 같은 `ClientIpResolver`를 사용한다. **Render에서는 `CF-Connecting-IP`의 단일 IP를, 로컬과 다른 환경에서는 연결 상대 주소를 버킷 키로 사용한다.** `X-Forwarded-For`의 첫 값과 `True-Client-IP`는 키로 사용하지 않는다.

## 프록시 뒤에서 실제 방문자 IP가 필요한 이유

`request.getRemoteAddr()`는 애플리케이션에 직접 연결한 상대의 주소다. 로컬에서 직접 호출하면 방문자 주소이지만, Render처럼 로드밸런서나 리버스 프록시가 앞에 있으면 프록시 주소가 될 수 있다.

이 주소를 그대로 제한 버킷의 키로 사용하면 서로 다른 방문자가 같은 버킷을 공유하거나, 같은 방문자의 요청이 여러 프록시 주소로 나뉘어 서로 다른 버킷에 들어갈 수 있다. 반대로 클라이언트가 임의로 넣은 전달 헤더를 믿으면 가짜 IP마다 새 버킷을 받을 수 있다. 실제 방문자를 구분하면서 클라이언트가 넣은 주소로 키를 바꾸지 못하게 해야 한다.

## 현재 IP 선택 규칙

1. Render가 자동으로 제공하는 `RENDER=true`를 Spring에서 읽는다. 로컬과 다른 환경의 기본값은 `false`다.
2. Render에서는 `CF-Connecting-IP`가 헤더 하나에 단일 숫자 IPv4 또는 IPv6로 들어왔을 때 사용한다.
3. 중복 헤더, 쉼표 목록, 호스트 이름, 포트나 IPv6 영역 식별자가 붙은 값, 잘못된 IP는 사용하지 않는다. DNS로 주소를 조회하지 않으며, 숫자 IP의 표기를 정규화해 같은 IPv6 주소가 같은 키를 사용하게 한다.
4. Render 외 환경이거나 유효한 헤더가 없으면 `getRemoteAddr()`로 돌아간다. `X-Forwarded-For`나 `True-Client-IP`로 대체하지 않는다.

Render에서는 별도 IP 설정을 등록할 필요가 없다. `server.forward-headers-strategy: none`으로 Tomcat의 자동 전달 헤더 해석을 끄고, 공통 resolver에서 한 번만 선택한다. 이전 `TRUSTED_PROXY_REGEX` 설정과 검증 클래스는 제거했으며 Render 환경변수와 로컬 `.env`에 남아 있는 값도 삭제할 수 있다.

이 신뢰는 확인한 **Cloudflare → Render 공개 서비스 → 백엔드** 경로를 전제로 한다. `RENDER=true`는 서버 환경 설정이지 요청 헤더가 아니다. 다른 서버에 이 값을 임의로 설정하거나 Cloudflare를 우회하는 직접·사설 진입 경로를 추가하면 동일한 신뢰 전제를 적용할 수 없다. 그런 경로는 별도로 검토해야 한다.

동일 공인 IP를 사용하는 공유기·NAT 뒤의 방문자는 같은 버킷을 사용할 수 있다. 또한 Cloudflare Workers 등의 중계 요청은 일반 브라우저 요청과 주소 의미가 다를 수 있다. 실제 사람 한 명을 고유하게 식별하는 기능은 아니다. [Render 환경변수](https://render.com/docs/environment-variables), [Render의 실제 IP 안내](https://render.com/articles/how-render-handles-ddos-attacks), [Cloudflare 헤더 설명](https://developers.cloudflare.com/fundamentals/reference/http-headers/)을 참고한다.

## 이전 문제 해결 기록

2026-09-10에 기록한 배포 확인에서는 같은 발신지에서 1초 간격으로 다섯 번 요청했을 때 `X-RateLimit-Remaining`이 다음과 같이 관찰됐다.

```text
14, 13, 14, 14, 14
```

당시 제한은 용량 15개, 4초마다 토큰 1개 회복이었다. 같은 버킷을 사용했다면 설명하기 어려운 회복이 나타나 IP 식별 경로를 조사했다. 당시 Render 대시보드에서는 Free 플랜의 단일 인스턴스를 확인했고, 응답의 `CF-RAY` 콜로 코드는 `KIX → NRT → KIX → NRT → KIX`로 바뀌었다. 프록시 경로 변화에 따라 다른 버킷을 사용했을 가능성을 원인으로 추정했다.

이 기록은 당시의 관찰과 추정이다. `CF-RAY`의 거점 변화만으로 애플리케이션에 연결한 프록시 IP가 바뀌었다고 확정할 수는 없다. 현재 배포의 인스턴스 수와 프록시 경로도 다시 확인해야 한다. 현재는 두 단계 제한을 사용하므로 위 수치나 감소 패턴을 그대로 정상 동작 기준으로 사용하지 않는다.

당시에는 Render 운영자의 첫 IP 설정 설명을 근거로 `X-Forwarded-For`의 첫 값을 사용하는 방식으로 대응했고, 정상 요청의 방문자 구분 문제를 해결했다. 다만 클라이언트가 미리 넣은 첫 값까지 제거되는지는 검증하지 않았다. [Render 게시판](https://feedback.render.com/features/p/send-the-correct-x-forwarded-for)의 과거 답변만으로 현재 배포의 위조 방지를 보장할 수는 없다.

이후 신뢰 프록시 설정을 도입하면서 `TRUSTED_PROXY_REGEX=(?!)`를 적용했지만, 어떤 프록시도 신뢰하지 않아 실제 방문자 주소를 복원하지 못했다. 배포 로그에서 `selectedIp=::1`에 해당하는 루프백 주소가 확인됐다. 이는 헤더 위조를 무시하는 대신 서로 다른 방문자를 같은 주소로 묶는 문제가 있었다.

### 배포에서 확인한 전달 헤더 동작

같은 배포의 정상 요청과 위조 요청을 진단 로그로 비교했다. 실제 공인 IP는 아래에서 `방문자IP`로 표시한다.

| 요청 | 백엔드에서 확인한 결과 |
| --- | --- |
| 일반 요청 | `X-Forwarded-For: 방문자IP, CloudflareIP, 내부프록시IP`; CF·True 헤더는 방문자IP |
| `X-Forwarded-For: 198.51.100.99` 위조 | 첫 값에 가짜 IP가 보존됨. CF·True 헤더는 방문자IP 유지 |
| `True-Client-IP` 위조 | 백엔드에 도달했지만 가짜 값은 방문자IP로 대체됨. CF 헤더도 방문자IP 유지 |
| `X-Real-IP` 위조 | CF 헤더는 방문자IP 유지 |
| CF 헤더에 예약 IPv4, 공인 IPv4 `1.1.1.1`, 공인 IPv6 지정 | 각각 403 및 오류 코드 1000, 해당 요청의 백엔드 진단 로그 없음 |
| 여러 IP 헤더와 CF 헤더를 함께 위조 | 403 및 오류 코드 1000 |

이 결과로 기존 첫 값 방식이 위조 값을 선택할 수 있음과, `(?!)` 설정에서는 루프백 주소로 묶임을 구분했다. 현재 방식은 실측에서 실제 IP를 유지한 `CF-Connecting-IP`를 확인한 Render 경로에서만 사용한다. 위조 요청의 차단 결과를 모든 중계 경로의 보안 보장으로 일반화하지 않는다. 서버 입구·외부 호출의 사용자별 버킷과 전체 외부 버킷 구조는 유지한다.

## 문제 해결

### 전달 헤더 비교 진단

Render의 **서비스 → Environment**에서 다음 값을 등록하고 적용하면 진단 로그를 켤 수 있다. 설정 변경은 재시작·재배포가 필요할 수 있다.

```dotenv
CLIENT_IP_DIAGNOSTICS_LOG_LEVEL=DEBUG
```

`/api/__ip_check__` 요청에만 `[CLIENT_IP_DIAGNOSTIC]` 로그가 남으며 일반 API 요청에는 남기지 않는다. Render의 **Logs**에서 이 태그로 필터링한 뒤 PowerShell에서 실행한다.

```powershell
$base = 'https://football-obs-backend.onrender.com'
$checkId = [Guid]::NewGuid().ToString('N')
$cases = @(
    @{ Name = 'normal'; Headers = @() },
    @{ Name = 'xff-spoof'; Headers = @('X-Forwarded-For: 198.51.100.99') },
    @{ Name = 'true-spoof'; Headers = @('True-Client-IP: 8.8.8.8') },
    @{ Name = 'real-spoof'; Headers = @('X-Real-IP: 1.1.1.1') },
    @{ Name = 'cf-spoof'; Headers = @('CF-Connecting-IP: 1.1.1.1') }
)
foreach ($case in $cases) {
    $requestArgs = @('--silent', '--show-error', '--max-time', '60',
        '--output', 'NUL', '--write-out', '%{http_code}')
    foreach ($header in $case.Headers) { $requestArgs += @('--header', $header) }
    $label = "$($case.Name)-$checkId"
    $status = & curl.exe @requestArgs "$base/api/__ip_check__?case=$label"
    [pscustomobject]@{ Case = $label; Status = $status }
}
```

로그의 `case`로 요청을 짝지어 Render의 일반·XFF·True·Real 헤더 위조 요청 모두에서 `selectedIp`가 같은 방문자 IP이고 `cfConnectingIp`와 일치하는지 확인한다. `remoteAddr`가 루프백이어도 선택 IP는 방문자 IP여야 한다. CF 헤더 위조 요청이 백엔드에 도달했다면 선택된 값을 확인하고, 과거의 403만으로 안전하다고 판단하지 않는다.

이 경로는 IP 선택 로그를 남기기 위한 것이며 별도 응답 API가 아니다. 현재 없는 경로에 대한 공통 예외 처리로 500이 반환될 수 있다. IP 검사는 상태나 잔여량이 아닌 진단 로그로 판단한다. 외부 축구 API는 호출하지 않는다.

확인 후에는 `CLIENT_IP_DIAGNOSTICS_LOG_LEVEL=INFO`로 바꾸고 적용한다. 진단 코드를 삭제하는 별도 커밋은 필요하지 않다. IP를 공유할 때 실제 주소를 가리더라도 같은 주소의 대응 관계와 가짜 값의 위치는 구분할 수 있게 남긴다.

### 증상별 확인

| 증상 | 확인할 내용 |
| --- | --- |
| Render에서 `selectedIp`가 루프백 | `RENDER=true`가 있는지, CF 헤더가 누락·중복·잘못된 값인지 확인한다. 이전 버전이 실행 중인지도 확인한다. |
| 로컬에서 CF 헤더가 무시됨 | 기본 동작이다. `RENDER`를 설정하지 않고 연결 상대 주소를 사용한다. |
| 일반 요청과 XFF 위조 요청에서 선택 IP가 달라짐 | 배포한 코드·환경과 진단 로그를 확인한다. 현재 resolver는 XFF를 키로 사용하지 않는다. |
| 같은 방문자의 잔여량이 예상과 다름 | 먼저 `X-RateLimit-Scope`를 비교하고, 토큰 회복·동시 요청·재시작·여러 인스턴스·선택 IP 변화를 확인한다. |
| 서로 다른 방문자가 전체 외부 제한을 공유함 | `upstream-global`은 모든 방문자의 공통 예산이므로 정상이다. 사용자별 구분과 별개로 판단한다. |

응답에서는 `X-RateLimit-Scope`, `X-RateLimit-Remaining`, `Retry-After`, `X-Retry-After-Millis`를 함께 본다. 서버 입구는 모든 API 요청을, 외부 제한은 실제 외부 호출만 세므로 다른 단계의 잔여량을 같은 버킷으로 비교하지 않는다. 자세한 내용은 [rate-limiting.md](rate-limiting.md)를 참고한다.

## 다른 곳에서도 IP가 필요해지면

IP 기반 통계나 차단 같은 기능에서도 주입받은 `ClientIpResolver`를 재사용한다. 각 기능이 원본 전달 헤더를 따로 해석하면 서로 다른 IP를 사용할 수 있다.

HTTP 요청 문맥이 없는 외부 호출은 `internal` 키를 사용한다. 독립 실행 CSV 업데이터는 자체 호출 간격을 유지하면서 별도 컨텍스트에서 외부 제한을 완화한다. 이는 방문자 IP 식별과 별도의 실행 경로다.
