package com.github.baek.footballobsbackend.error;

import lombok.Getter;

@Getter
public class ApiException extends RuntimeException {
    private final ErrorCode errorCode;
    public ApiException(ErrorCode code){ super(code.getMessage()); this.errorCode = code; }
    public ApiException(ErrorCode code, String message){ super(message); this.errorCode = code; }
}
