package com.github.baek.footballobsbackend.dto.Layer1;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EventDto {
    private int elapsed;
    private Integer extra;      // null 가능

    private String side;        // "home" | "away"
    private long teamId;

    private long playerId;
    private String playerName;  // 한글 우선, 없으면 영문
    private String playerNameKoLong; // 한글 풀네임. players.csv에 있을 때만 제공

    private Long assistId;      // null 가능 (subst이면 교체투입 선수)
    private String assistName;  // null 가능, 한글 우선
    private String assistNameKoLong; // 한글 풀네임. players.csv에 있을 때만 제공

    private String type;        // "Goal" | "Card" | "subst" | "Var"
    private String detail;      // "Normal Goal" | "Penalty" | "Missed Penalty" | "Yellow Card" | "Substitution 1" | ...
    private String comments;    // 페널티 슛아웃 이벤트는 "Penalty Shootout", 일반은 "Foul", "Tripping", "Unsportsmanlike conduct" 등등 구체적인 사유
}
