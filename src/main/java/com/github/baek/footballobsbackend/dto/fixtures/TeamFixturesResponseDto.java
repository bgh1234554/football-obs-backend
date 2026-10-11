package com.github.baek.footballobsbackend.dto.fixtures;

import java.util.List;

/** 조회한 팀 ID와 최대 5개의 경기 목록. */
public record TeamFixturesResponseDto(long teamId, List<TeamFixtureDto> matches) {
}
