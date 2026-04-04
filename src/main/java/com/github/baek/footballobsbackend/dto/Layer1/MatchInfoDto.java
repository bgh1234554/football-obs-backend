package com.github.baek.footballobsbackend.dto.Layer1;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class MatchInfoDto {
    private long fixtureId;
    private String leagueName;
    private String leagueRound;
    private String leagueLogoUrl;

    private String venueName;    // 경기장 한글 이름. venues.csv에 없으면 API 영문 이름 fallback
    private String venueCity;    // 경기 도시명 (API 원본 그대로)

    private String status;      // "1H"|"HT"|"2H"|"ET1"|"ET2"|"PSO"|"FT"|"NS"
    private int elapsed;
    private Integer extra;      // null 가능

    private long homeTeamId;
    private String homeTeamName;
    private String homeTeamLogo;
    private String homeTeamFlagUrl;
    private int homeScore;
    private Integer homePenaltyScore;   // 페널티 슛아웃 점수. 정규/연장으로 끝난 경기는 null
    private String homePrimaryColor;
    private String homeNumberColor;

    private long awayTeamId;
    private String awayTeamName;
    private String awayTeamLogo;
    private String awayTeamFlagUrl;
    private int awayScore;
    private Integer awayPenaltyScore;   // 페널티 슛아웃 점수. 정규/연장으로 끝난 경기는 null
    private String awayPrimaryColor;
    private String awayNumberColor;

    private String refereeName;  // 국가가 있으면 "이름 (국가)", 없으면 이름만 반환. 한글 없으면 영문 이름 fallback

}
