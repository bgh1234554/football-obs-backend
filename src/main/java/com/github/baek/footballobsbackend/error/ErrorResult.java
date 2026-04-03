package com.github.baek.footballobsbackend.error;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResult(String code, String message, Integer status, String path, Instant timestamp) {
}
