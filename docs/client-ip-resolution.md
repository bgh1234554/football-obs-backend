# 클라이언트 IP 식별

이 문서는 Render 프록시 뒤에서 실제 방문자 IP를 구분하는 과정과, 전달 헤더 위조 테스트에서 확인한 문제, 최종 수정 구조 및 배포 검증 결과를 정리한다.

## 1. 처음 발생한 문제: 방문자 대신 프록시 주소로 제한

Render에서 백엔드로 들어오는 요청은 Cloudflare와 Render의 프록시를 거친다.

```text
방문자 → Cloudflare → Render 프록시 → 백엔드
```

`request.getRemoteAddr()`는 백엔드에 직접 연결한 상대의 주소다. 프록시 뒤에서는 실제 방문자 대신 프록시 주소를 반환할 수 있다. 이를 IP별 요청 제한의 키로 사용하면 다음 문제가 생긴다.

- 서로 다른 방문자가 같은 프록시를 거치면 하나의 버킷을 공유할 수 있다.
- 같은 방문자라도 연결 프록시가 달라지면 여러 버킷으로 나뉠 수 있다.

이 프로젝트에서 필요한 것은 프록시 주소별 제한이 아니라 **방문자의 공인 IP별 제한**이었다.

### 당시 관찰과 첫 번째 수정

2026-09-10에 기록한 배포 확인에서는 같은 발신지에서 1초 간격으로 다섯 번 요청했을 때 `X-RateLimit-Remaining`이 다음과 같이 관찰됐다.

```text
14 → 13 → 14 → 14 → 14
```

당시 정책은 용량 15개, 4초마다 토큰 1개 회복이었다. 같은 버킷을 계속 사용한다고 보기 어려운 변화가 나타나 IP 식별 경로를 조사했다. 당시 Render 대시보드에서는 단일 인스턴스를 확인했고, `CF-RAY`의 거점 코드도 `KIX → NRT → KIX → NRT → KIX`로 바뀌었다. 이에 따라 프록시 경로 변화로 다른 버킷을 사용했을 가능성을 의심했다.

다만 거점 변화만으로 실제 연결 프록시 IP가 바뀌었다고 확정한 것은 아니다. 이 부분은 당시의 관찰과 원인 추정으로 남긴다.

첫 번째 수정에서는 `X-Forwarded-For`의 첫 주소를 방문자 IP로 사용했다. [Render 게시판](https://feedback.render.com/features/p/send-the-correct-x-forwarded-for)의 운영자 답변에서 첫 IP를 실제 방문자 IP로 설정한다고 설명한 것을 근거로 삼았다.

```text
X-Forwarded-For: 방문자IP, CloudflareIP, 내부프록시IP
                └ 기존 코드가 선택한 값
```

이 방식으로 정상 요청의 방문자 구분 문제는 해결했다. 하지만 **클라이언트가 처음부터 가짜 전달 헤더를 넣었을 때도 안전한지는 검증하지 않았다.** 당시의 기능상 해결과 헤더 위조에 대한 보안 검증은 구분해야 한다.

## 2. 이번 검토: 버킷 자체보다 IP 선택이 문제

이후 요청 제한을 서버 입구 보호와 실제 외부 호출 보호로 분리했다.

- 서버 입구 제한은 모든 API 요청을 IP별로 센다.
- 외부 호출 제한은 캐시로 해결되지 않아 실제 외부 HTTP 호출을 시도할 때 사용자별·전체 버킷을 확인한다.

이 구조에서도 버킷의 키가 올바른 방문자 IP여야 한다. 구조 분리 때문에 기존 IP 식별이 무효가 된 것은 아니다. 이번에 별도로 제기된 문제는 **`X-Forwarded-For`의 첫 값을 클라이언트가 조작할 수 있는가**였다.

첫 값이 조작 가능하다면 요청마다 다른 가짜 IP를 넣어 새 버킷을 받을 수 있다. 전체 외부 호출 버킷은 남아 있지만, 한 사용자가 여러 IP 버킷을 만들어 전체 예산을 소모할 수 있다.

### 신뢰 프록시 방식으로 바꿨을 때의 문제

검토 과정에서 Spring/Tomcat의 신뢰 프록시 기능을 사용하도록 변경하고, `TRUSTED_PROXY_REGEX=(?!)`를 적용했다. 이 정규식은 어떤 주소에도 일치하지 않으므로 전달 헤더를 통한 방문자 주소 복원을 하지 않는다.

실제 배포 로그에서는 다음 값이 확인됐다.

```text
remoteAddr=0:0:0:0:0:0:0:1
selectedIp=0:0:0:0:0:0:0:1
```

두 값은 IPv6 루프백 주소 `::1`이다. 이 상태에서는 가짜 전달 헤더를 무시하지만, 정상 방문자도 로컬 프록시 연결 주소로 묶인다. 따라서 원래 목적이었던 방문자별 구분을 충족하지 못했다.

## 3. 검증 방법: 잔여량 대신 실제 선택 IP 확인

처음에는 일반 요청과 위조 헤더 요청의 `X-RateLimit-Remaining`을 비교했다. 잔여량은 안정적으로 소모됐지만 이것만으로는 정상이라고 판단할 수 없었다. 실제 방문자 IP로 같은 버킷을 쓰는 경우와, 모두 같은 프록시 IP로 묶이는 경우가 같은 결과를 낼 수 있기 때문이다. 토큰 회복과 동시 요청도 잔여량에 영향을 준다.

이를 구분하기 위해 `ClientIpResolver`에 선택적으로 켜는 진단 로그를 추가했다.

| 로그 필드 | 확인 목적 |
| --- | --- |
| `case` | 일반 요청과 각 위조 요청을 구분하고 같은 검사 실행끼리 연결 |
| `remoteAddr` | 백엔드에 직접 연결한 상대 주소 확인 |
| `selectedIp` | 실제 버킷 키로 선택한 IP 확인 |
| `xForwardedFor` | 가짜 값이 전달 체인에 보존되는지 확인 |
| `cfConnectingIp` | Cloudflare가 전달한 방문자 주소 확인 |
| `trueClientIp` | 다른 방문자 IP 헤더와 비교 |

진단 로그는 DEBUG를 켠 상태의 `/api/__ip_check__` 요청에만 남긴다. 일반 API 요청에는 진단 로그를 남기지 않는다. 이 경로는 IP 선택 코드를 통과해 로그를 기록하기 위한 경로이며 외부 축구 API는 호출하지 않는다.

### 수정 전 실제 요청에서 확인한 결과

PowerShell의 `curl.exe`로 일반 요청과 위조 요청을 보내고 Render의 진단 로그를 비교했다. 실제 방문자 주소는 아래에서 `방문자IP`로 표시한다.

| 검사 | 관찰 결과 | 판단 |
| --- | --- | --- |
| 일반 요청 | XFF 첫 값과 CF·True 헤더는 방문자IP, 선택 IP는 `::1` | 기존 헤더에는 방문자 주소가 있지만 현재 설정은 사용하지 않음 |
| XFF 첫 값 위조 | `가짜IP, 방문자IP, 프록시IP...`로 전달됨 | 기존 첫 값 선택은 위조 IP를 버킷 키로 사용할 수 있음 |
| `True-Client-IP` 위조 | 백엔드에서 방문자IP로 대체돼 있었고 CF 헤더도 방문자IP 유지 | 이 검사에서는 위조 값이 유지되지 않음 |
| `X-Real-IP` 위조 | CF 헤더가 방문자IP 유지 | 다른 헤더를 바꿔도 CF 헤더가 영향을 받지 않음 |
| CF 헤더 자체 위조 | 403, 오류 코드 1000; 해당 요청의 백엔드 진단 로그 없음 | 테스트한 요청은 백엔드에 도달하기 전에 차단됨 |

처음 CF 헤더 위조에는 예약 주소를 사용했다. 주소 종류에 따른 거부일 가능성을 구분하기 위해 공인 IPv4 `1.1.1.1`과 공인 IPv6도 추가로 넣었다. 이 요청들과 여러 IP 헤더를 함께 위조한 요청도 403으로 차단됐다.

이 검증으로 두 문제를 따로 확인했다. **기존 첫 값 방식은 위조에 취약했고, `(?!)` 설정은 방문자를 루프백으로 묶었다.** 정상 요청에서 문제를 해결했던 기존 방식으로 단순 복원하는 것만으로는 충분하지 않았다.

## 4. 최종 수정: Render에서 CF 헤더로 방문자 구분

실측 결과를 바탕으로 두 단계 버킷 구조는 유지하고 IP 선택 로직만 수정했다.

1. Render가 자동 제공하는 서버 환경변수 `RENDER=true`를 Spring에서 읽는다. 로컬과 다른 환경의 기본값은 `false`다.
2. Render에서는 `CF-Connecting-IP`가 헤더 하나에 단일 숫자 IPv4 또는 IPv6로 들어온 경우 사용한다.
3. 중복 헤더, 쉼표 목록, 호스트 이름, 포트 또는 IPv6 영역 식별자가 붙은 값, 잘못된 주소는 사용하지 않는다.
4. 숫자 주소만 처리해 DNS 조회를 하지 않고, 표기를 정규화해 같은 IPv6 주소가 같은 키를 사용하게 한다.
5. Render 외 환경이거나 유효한 CF 헤더가 없으면 연결 상대 주소로 돌아간다. XFF와 True 헤더를 대체 키로 사용하지 않는다.

`ClientIpResolver`를 Spring 빈으로 만들고 서버 입구 제한과 외부 호출 제한에 같은 인스턴스를 주입했다. 어느 단계에서든 같은 규칙으로 선택한 주소를 사용한다.

`server.forward-headers-strategy: none`으로 Tomcat의 자동 전달 헤더 해석을 끄고 공통 resolver에서 IP를 선택한다. 불필요해진 `TRUSTED_PROXY_REGEX` 설정과 검증 클래스는 제거했다. Render 환경변수와 로컬 `.env`에 남은 해당 값도 삭제할 수 있다.

### 로컬 테스트

전체 테스트 140개를 실행해 통과했고, 마지막으로 보강한 Spring 설정 주입 테스트도 통과했다. IP 식별과 관련해서는 다음 동작을 확인했다.

- Render 설정에서 CF 주소를 사용하고 로컬에서는 같은 헤더를 무시한다.
- 누락·잘못된 값·중복 헤더가 다른 전달 헤더의 가짜 주소로 대체되지 않는다.
- 같은 IPv6 주소의 축약·확장 표기가 같은 키를 사용한다.
- 같은 연결 상대 주소를 거쳐도 CF 주소가 다른 방문자는 서버·외부 제한에서 각각 다른 버킷을 사용한다.
- XFF 가짜 값만 바꿔서는 두 제한기에서 새 버킷을 받지 못한다.
- 기존 전체 외부 버킷, 차감·회복·동시성 계약은 유지된다.

## 5. 수정 후 배포 검증과 완료 결과

수정 버전을 배포한 뒤, PowerShell에서 다음 14개 케이스를 한 번에 보냈다. 응답 상태와 같은 검사 식별자가 포함된 Render 진단 로그를 함께 비교했다.

| 구분 | 케이스 | 결과 |
| --- | --- | --- |
| 일반 요청 | 검사 전·후 2건 | 실제 방문자 IP를 동일하게 선택 |
| XFF 위조 | 단일 값·주소 목록·중복 헤더 3건 | 가짜 값이 XFF에 남아도 실제 방문자 IP 선택 |
| 다른 전달 헤더 위조 | True·Real·Forwarded 3건 | 실제 방문자 IP 선택 |
| CF 헤더 위조 | IPv4·IPv6·목록·중복·잘못된 값 5건 | 모두 403 |
| 여러 헤더 동시 위조 | CF를 포함한 1건 | 403 |

백엔드에 도달한 8건은 모두 `selectedIp`가 같은 방문자 IP였고 `cfConnectingIp`와 일치했다. 요청마다 Cloudflare·내부 프록시 주소가 달라져도 선택 IP는 유지됐다. 배포 전과 달리 루프백 주소를 버킷 키로 사용하지 않는 것을 확인했다.

CF 헤더를 위조한 6건은 모두 403이었다. 나머지 8건의 진단 경로 응답은 500이었지만, 이는 없는 경로에 대한 현재 공통 예외 처리 결과다. IP 식별의 성공·실패는 이 500이 아니라 진단 로그의 선택 IP로 판단했다. 정상 경기 API가 500이라는 의미는 아니다.

**검증한 Render 공개 요청 경로에서 방문자 IP 선택과 이번 헤더 위조 케이스 검증을 완료했다.** 서로 다른 방문자의 버킷 분리는 로컬 테스트에서 확인했으며, 배포 검사는 같은 발신지에서 전달 헤더와 선택 IP의 관계를 확인한 것이다.

확인이 끝나면 `CLIENT_IP_DIAGNOSTICS_LOG_LEVEL=INFO`로 바꾸고 적용해 진단 로그를 끈다. 진단 코드를 삭제하는 별도 커밋은 필요하지 않다.

## 6. 최종 구조와 적용 범위

```text
방문자 → Cloudflare → Render 공개 서비스 → 백엔드
                                           │
                                   ClientIpResolver
                                           │
                    Render + 유효한 CF 헤더 ─┤→ 정규화한 CF 주소
                    그 외 ──────────────────┘→ 연결 상대 주소
                                           │
                                서버 입구 IP별 제한
                                           │
                                  Controller / Service
                                           │
                                    CdnResponseCache
                                    ├─ 적중 → 응답
                                    └─ 미적중 등 실제 외부 호출 필요
                                           │
                                같은 규칙으로 방문자 IP 선택
                                           │
                              사용자별·전체 외부 버킷 함께 확인
                                           │
                                  외부 HTTP 시도 → 응답
```

서버 입구 버킷은 캐시 적중을 포함한 API 요청을 세고, 외부 버킷은 실제 외부 HTTP 시도를 센다. 두 단계의 사용자별 키는 같은 resolver가 선택한 IP다. 전체 외부 버킷은 방문자와 관계없이 공유한다. HTTP 요청 문맥이 없는 외부 호출은 `internal` 키를 쓰며, 독립 실행 CSV 업데이터는 자체 호출 간격을 유지하면서 별도 컨텍스트에서 외부 제한을 완화한다. 자세한 차감 규칙은 [rate-limiting.md](rate-limiting.md), 클래스별 흐름은 [rate-limiting-architecture.md](rate-limiting-architecture.md)를 참고한다.

**최종 결론은 버킷이 없거나 차감이 고장 난 문제가 아니라, 버킷의 키로 사용할 IP의 출처를 잘못 선택했던 문제라는 것이다.** 기존의 정상 방문자 구분 의도와 두 단계 제한 구조는 유지하고, 확인한 Render 공개 경로에서 CF 헤더를 사용하도록 수정했다.

이 신뢰는 **Cloudflare → Render 공개 서비스 → 백엔드** 경로를 전제로 한다. `RENDER=true`는 요청 헤더가 아닌 서버 환경 설정이며, 다른 서버에 임의로 설정하거나 Cloudflare를 우회하는 직접·사설 진입 경로를 추가하면 별도로 검토해야 한다. 이번 검사를 모든 중계 경로의 보안 보장으로 일반화하지 않는다.

공유기·NAT 뒤에서 같은 공인 IP를 쓰는 방문자는 같은 버킷을 사용할 수 있다. Cloudflare Workers 같은 중계 요청은 일반 브라우저 요청과 주소 의미가 다를 수 있다. 이 기능은 실제 사람 한 명을 고유하게 식별하는 기능이 아니다. 관련 공식 자료는 [Render 환경변수](https://render.com/docs/environment-variables), [Render의 실제 IP 안내](https://render.com/articles/how-render-handles-ddos-attacks), [Cloudflare 헤더 설명](https://developers.cloudflare.com/fundamentals/reference/http-headers/)을 참고한다.

## 7. 재검사 및 문제 해결

### 진단 로그 켜기

Render의 **서비스 → Environment**에서 아래 값을 등록하고 적용한다. 환경변수 변경은 재시작·재배포가 필요할 수 있다.

```dotenv
CLIENT_IP_DIAGNOSTICS_LOG_LEVEL=DEBUG
```

Render **Logs**에서 `[CLIENT_IP_DIAGNOSTIC]` 태그와 요청의 `case`로 검색한다. 실제 주소를 공유할 때는 같은 주소의 대응 관계와 가짜 값의 위치를 알아볼 수 있게 가린다.

### 전체 케이스 실행

배포가 끝난 뒤 PowerShell에서 다음 명령을 실행한다. CF 헤더 위조 케이스는 엣지에서 거부될 수 있으며, 도달한 요청은 `selectedIp`와 CF 주소를 비교한다.

```powershell
$base = 'https://football-obs-backend.onrender.com'
$checkId = [Guid]::NewGuid().ToString('N')
$cases = @(
    @{ Name = 'normal-before'; Headers = @() }
    @{ Name = 'xff-single'; Headers = @('X-Forwarded-For: 198.51.100.99') }
    @{ Name = 'xff-chain'; Headers = @('X-Forwarded-For: 1.1.1.1, 8.8.8.8') }
    @{ Name = 'xff-duplicate'; Headers = @('X-Forwarded-For: 1.1.1.1', 'X-Forwarded-For: 8.8.8.8') }
    @{ Name = 'true-client'; Headers = @('True-Client-IP: 8.8.8.8') }
    @{ Name = 'x-real-ip'; Headers = @('X-Real-IP: 1.1.1.1') }
    @{ Name = 'forwarded'; Headers = @('Forwarded: for=1.1.1.1;proto=https') }
    @{ Name = 'cf-ipv4'; Headers = @('CF-Connecting-IP: 1.1.1.1') }
    @{ Name = 'cf-ipv6'; Headers = @('CF-Connecting-IP: 2606:4700:4700::1111') }
    @{ Name = 'cf-list'; Headers = @('CF-Connecting-IP: 1.1.1.1, 8.8.8.8') }
    @{ Name = 'cf-duplicate'; Headers = @('CF-Connecting-IP: 1.1.1.1', 'CF-Connecting-IP: 8.8.8.8') }
    @{ Name = 'cf-invalid'; Headers = @('CF-Connecting-IP: invalid-ip') }
    @{ Name = 'all-spoof'; Headers = @(
        'X-Forwarded-For: 9.9.9.9', 'CF-Connecting-IP: 1.1.1.1',
        'True-Client-IP: 8.8.8.8', 'X-Real-IP: 9.9.9.9', 'Forwarded: for=8.8.8.8;proto=https'
    ) }
    @{ Name = 'normal-after'; Headers = @() }
)
$results = foreach ($case in $cases) {
    $label = "$($case.Name)-$checkId"
    $requestArgs = @('--silent', '--show-error', '--max-time', '60',
        '--output', 'NUL', '--write-out', '%{http_code}', '--header', 'Cache-Control: no-cache')
    foreach ($header in $case.Headers) { $requestArgs += @('--header', $header) }
    $status = & curl.exe @requestArgs "$base/api/__ip_check__?case=$label"
    $exitCode = $LASTEXITCODE
    [pscustomobject]@{ Case = $case.Name; Status = $status; CurlExit = $exitCode }
}
$results | Format-Table -AutoSize
Write-Host "Render Logs에서 검색할 식별자: $checkId"
```

선택 IP는 같은 공인 IP에서 보낸 요청끼리 같아야 하고, 프록시 주소나 XFF 가짜 값에 따라 바뀌면 안 된다. CF 위조 요청이 새로 백엔드에 도달했다면 선택된 값을 확인하고, 과거의 403 결과만으로 안전하다고 판단하지 않는다.

### 증상별 확인

| 증상 | 확인할 내용 |
| --- | --- |
| Render에서 선택 IP가 루프백 | `RENDER=true`, CF 헤더의 누락·중복·잘못된 값, 배포 버전을 확인한다. |
| 로컬에서 CF 헤더가 무시됨 | 정상 동작이다. 로컬에서는 연결 상대 주소를 사용한다. |
| 일반 요청과 XFF 위조 요청의 선택 IP가 다름 | 현재 resolver는 XFF를 사용하지 않는다. 실행 중인 코드·환경과 로그를 확인한다. |
| 같은 방문자의 잔여량이 예상과 다름 | 제한 종류, 토큰 회복, 동시 요청, 재시작, 여러 인스턴스, 선택 IP 변화를 함께 확인한다. |
| 서로 다른 방문자가 전체 외부 제한을 공유함 | `upstream-global`은 원래 공통 예산이다. IP별 구분과 별개로 판단한다. |
| 진단 경로에서 500 응답 | 없는 경로의 예외 처리 결과일 수 있다. IP 검사는 대응하는 진단 로그로 판단한다. |

다른 기능에서도 IP가 필요하면 주입받은 `ClientIpResolver`를 재사용한다. 각 기능이 전달 헤더를 따로 해석하면 서버 입구와 외부 호출 제한이 서로 다른 주소를 사용할 수 있다.
