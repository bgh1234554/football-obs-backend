package com.github.baek.footballobsbackend.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum ErrorCode {
    FIXTURE_NOT_FOUND(HttpStatus.NOT_FOUND,"해당 ID에 대한 일정 정보를 찾을 수 없습니다."),
    PLAYER_NOT_FOUND(HttpStatus.NOT_FOUND,"해당 선수를 찾을 수 없습니다."),
    STAT_NOT_AVAILABLE(HttpStatus.NOT_FOUND,"해당 선수에 대한 스탯이 제공되지 않습니다."),
    H2H_NOT_AVAILABLE(HttpStatus.NOT_FOUND,"해당 경기에 대한 상대 전적 스탯이 없거나 제공되지 않습니다."),
    RATE_LIMIT_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS,"요청이 너무 많습니다. 잠시 후 다시 시도해주세요."),
    // API Football(BunnyCDN 경유)이 4xx/5xx로 응답할 때 사용. 우리 서버 버그가 아니라 업스트림 문제이므로
    // GlobalExceptionAdvice의 catch-all(ERROR 로그 + "서버 내부에 오류 발생")로 새지 않게 ApiFootballClient에서
    // RestClientResponseException을 잡아 이걸로 변환한다.
    UPSTREAM_API_ERROR(HttpStatus.BAD_GATEWAY,"축구 데이터 제공처(API-Football)에 일시적인 오류가 발생했습니다. 잠시 후 다시 시도해주세요.");
    private final HttpStatus status;
    private final String message;
}