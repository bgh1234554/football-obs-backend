package com.github.baek.footballobsbackend.error;

/**
 * 외부 HTTP 요청을 보내기 전에 한도가 부족해 거절됐음을 나타낸다.
 * 기존 RATE_LIMIT_EXCEEDED 코드와 429를 유지하면서 제한 종류·재시도 정보를 추가한다.
 * GlobalExceptionAdvice가 이 값을 응답 헤더로 옮긴다.
 * 외부 서버가 돌려준 오류를 뜻하는 UPSTREAM_API_ERROR와 구별해야 한다.
 */
public class UpstreamRateLimitException extends ApiException {
    private final String scope;
    private final long limit;
    private final long retryMillis;

    /** 원본 JSON이나 캐시 항목 대신 응답에 필요한 작은 제한 정보만 보관한다. */
    public UpstreamRateLimitException(String scope, long limit, long retryMillis) {
        super(ErrorCode.RATE_LIMIT_EXCEEDED);
        this.scope = scope;
        this.limit = limit;
        this.retryMillis = retryMillis;
    }
    public String getScope() { return scope; }
    public long getLimit() { return limit; }
    public long getRetryMillis() { return retryMillis; }
}
