package com.github.baek.footballobsbackend.dto.stats.Layer1;

import com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerSeasonStatDto {
    private StatTeamDto team;
    private StatLeagueDto league;
    private StatGamesDto games;
    private StatSubstitutesDto substitutes;
    private StatShotsDto shots;
    private StatGoalsDto goals;
    private StatPassesDto passes;
    private StatTacklesDto tackles;
    private StatDuelsDto duels;
    private StatDribblesDto dribbles;
    private StatFoulsDto fouls;
    private StatCardsDto cards;
    private StatPenaltyDto penalty;
}
