package com.github.baek.footballobsbackend.dto.fixtures;

import com.github.baek.footballobsbackend.dto.fixtures.Layer1.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FixtureResponseDto {
    private MatchInfoDto matchInfo;
    private List<EventDto> events;
    private List<TeamStatsDto> teamStats;
    private LineupDto homeLineup;
    private LineupDto awayLineup;
    private List<PlayerStatsDto> playerStats;
    private List<InjuryDto> homeInjuries;   // 홈팀 부상/결장 선수
    private List<InjuryDto> awayInjuries;   // 원정팀 부상/결장 선수
}
