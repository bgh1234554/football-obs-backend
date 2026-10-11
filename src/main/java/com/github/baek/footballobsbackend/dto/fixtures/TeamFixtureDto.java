package com.github.baek.footballobsbackend.dto.fixtures;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.Value;

/** 최근 경기 결과와 예정 일정에 공통으로 사용하는 경기 요약. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamFixtureDto {
    long fixtureId;
    String date;
    String status;
    long homeTeamId;
    String homeTeamName;       // 한글 풀네임 우선
    String homeTeamNameShort;  // 한글 단축명 우선
    String homeTeamLogo;
    Integer homeScore;
    Integer homePenaltyScore;
    long awayTeamId;
    String awayTeamName;       // 한글 풀네임 우선
    String awayTeamNameShort;  // 한글 단축명 우선
    String awayTeamLogo;
    Integer awayScore;
    Integer awayPenaltyScore;
}
