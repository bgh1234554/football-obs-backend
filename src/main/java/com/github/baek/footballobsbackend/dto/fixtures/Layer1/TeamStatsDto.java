package com.github.baek.footballobsbackend.dto.fixtures.Layer1;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TeamStatsDto {
    private long teamId;
    private String side;            // "home" | "away"

    private Integer shotsOnGoal;
    private Integer shotsOffGoal;
    private Integer totalShots;
    private Integer blockedShots;
    private Integer shotsInsidebox;
    private Integer shotsOutsidebox;

    private Integer fouls;
    private Integer cornerKicks;
    private Integer offsides;
    private String ballPossession;  // "31%"

    private Integer yellowCards;
    private Integer redCards;
    private Integer goalkeeperSaves;

    private Integer totalPasses;
    private Integer passesAccurate;
    private String passesPercent;   // "60%"

    private String expectedGoals;
    private Integer goalsPrevented;
}
