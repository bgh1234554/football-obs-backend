package com.github.baek.footballobsbackend.error;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

@AllArgsConstructor
@Getter
public enum ErrorCode {
    FIXTURE_NOT_FOUND(HttpStatus.NOT_FOUND,"해당 ID에 대한 일정 정보를 찾을 수 없습니다."),
    PLAYER_NOT_FOUND(HttpStatus.NOT_FOUND,"해당 선수를 찾을 수 없습니다."),
    STAT_NOT_AVAILABLE(HttpStatus.NOT_FOUND,"해당 선수에 대한 스탯이 제공되지 않습니다.");
    private final HttpStatus status;
    private final String message;
}