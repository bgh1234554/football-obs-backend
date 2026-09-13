package com.github.baek.footballobsbackend.dto.fixtures.Layer1;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlayerStatsDto {
    private long playerId;
    private String playerName;      // 한글 우선, 없으면 영문
    private String playerNameKoLong; // 한글 풀네임. players.csv에 있을 때만 제공
    private String playerPhotoUrl;  // media CDN URL

    private String side;            // "home" | "away"

    private Integer minutes;
    private int number;
    private String position;
    private String rating;          // "7.5" (문자열)
    private boolean captain;
    private boolean substitute;

    private Integer shotsTotal;
    private Integer shotsOn;

    private Integer goalsScored;
    private Integer goalsConceded;
    private Integer assists;
    private Integer saves;

    private Integer passesTotal;
    private Integer passesKey;
    private Integer passesSuccessful; // 패스 성공 횟수. API-Football의 passes.accuracy는 실제로 이 값(개수)을 담고 있음
    private String passesAccuracy;    // 패스 성공률(%). passesSuccessful/passesTotal로 직접 계산한 값 (% 기호 없음)

    private Integer tacklesTotal;
    private Integer tacklesBlocks;
    private Integer tacklesInterceptions;

    private Integer duelsTotal;
    private Integer duelsWon;

    private Integer dribblesAttempts;
    private Integer dribblesSuccess;

    private Integer foulsDrawn;
    private Integer foulsCommitted;

    private int yellowCards;
    private int redCards;
}
