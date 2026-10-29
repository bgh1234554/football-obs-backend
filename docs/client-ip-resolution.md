# 클라이언트 IP 식별

`RateLimitInterceptor`와 `UpstreamRateLimiter`는 공통 유틸 `ClientIpResolver.resolve()`를 사용한다. 이 메서드는 Tomcat이 검증한 `request.getRemoteAddr()`를 반환하며, 원본 `X-Forwarded-For`를 직접 읽지 않는다.

## 전달 헤더와 신뢰 프록시

`server.forward-headers-strategy: native`로 Tomcat의 `RemoteIpValve`를 사용한다. 신뢰 프록시에서 온 요청에 한해 `X-Forwarded-For`를 오른쪽부터 검사하고, 신뢰하지 않는 첫 주소를 방문자 IP로 사용한다. 앞쪽에 클라이언트가 넣은 가짜 값이 있어도 그 값까지 거슬러 올라가지 않는다.

Spring Boot 설정은 [공식 문서](https://docs.spring.io/spring-boot/3.5/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server)를 따른다.

`TRUSTED_PROXY_REGEX`는 실제 배포 환경의 프록시 주소에만 일치하는 Java 정규식으로 설정한다. 기본값 `(?!)`는 어떤 주소에도 일치하지 않아 전달 헤더를 신뢰하지 않는다. 빈 값은 Tomcat에서 전체 프록시 신뢰를 뜻하므로 애플리케이션 시작 시 거부한다.

예를 들어 직접 연결하는 프록시가 `10.20.0.5`이고 앞단 프록시가 `10.20.0.6`인 환경이라면 다음처럼 지정한다.

```dotenv
TRUSTED_PROXY_REGEX=10[.]20[.]0[.]5|10[.]20[.]0[.]6
```

이 주소는 설명용 예시다. 실제 연결 경로와 신뢰할 프록시 주소를 확인해 설정해야 한다. 프록시 설정이 없으면 연결 상대 주소를 기준으로 제한하므로, 프록시 뒤의 여러 방문자가 같은 버킷을 공유할 수 있다.

## Render 배포 확인

기존 문서는 Render가 항상 첫 IP를 실제 방문자 IP로 덮어쓴다고 해석했지만, [Render 게시판](https://feedback.render.com/features/p/send-the-correct-x-forwarded-for)에는 과거 운영자 답변과 이후 헤더 추가 동작을 보고한 댓글이 함께 있다. 이를 현재 배포의 위조 방지 보장으로 사용하지 않는다.

Render에서 실제 연결 상대 주소와 전달 헤더 체인을 확인한 뒤 신뢰 프록시 정규식을 설정한다. 정상 요청과 임의의 `X-Forwarded-For`를 넣은 요청에서 두 제한기가 같은 방문자 버킷을 사용하는지 확인한다. 맨 앞 또는 맨 뒤 값을 무조건 선택하거나 모든 주소를 신뢰하는 설정은 사용하지 않는다.
