package com.github.baek.footballobsbackend.dto.search;

import java.util.List;

/** 원본 API 한 페이지를 반환하며 화면의 5명 단위 분할은 프론트에서 처리한다. */
public record PlayerSearchResponseDto(List<PlayerSearchItemDto> players, int page, int totalPages) {}
