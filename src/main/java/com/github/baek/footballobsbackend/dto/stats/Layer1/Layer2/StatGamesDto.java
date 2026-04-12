package com.github.baek.footballobsbackend.dto.stats.Layer1.Layer2;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StatGamesDto {
    private Integer appearences;
    private Integer lineups;
    private Integer minutes;
    private Integer number;    // null 가능 (일부 대회에서 미제공)
    private String position;
    private String rating;     // "7.46" 등 소수점 문자열
    private boolean captain;
}
