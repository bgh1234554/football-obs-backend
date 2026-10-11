package com.github.baek.footballobsbackend.dto.fixtures;

import java.util.List;

/** 조회한 팀 ID와 최근 최대 6경기 또는 예정 최대 5경기 목록. */
public record TeamFixturesResponseDto(long teamId, List<TeamFixtureDto> matches) {
}
