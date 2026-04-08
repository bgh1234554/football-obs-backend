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
    private String homeTeamName;       // 한글 풀네임 (예: 맨체스터 유나이티드)
    private String homeTeamNameShort;  // 한글 단축명 (예: 맨 유나이티드). 없으면 homeTeamName과 동일
    private String homeTeamLogo;
    private String homeTeamFaUrl;
    private int homeScore;
    private Integer homePenaltyScore;   // 페널티 슛아웃 점수. 정규/연장으로 끝난 경기는 null
    private String homePrimaryColor;
    private String homeNumberColor;

    private long awayTeamId;
    private String awayTeamName;       // 한글 풀네임
    private String awayTeamNameShort;  // 한글 단축명. 없으면 awayTeamName과 동일
    private String awayTeamLogo;
    private String awayTeamFaUrl;
    private int awayScore;
    private Integer awayPenaltyScore;   // 페널티 슛아웃 점수. 정규/연장으로 끝난 경기는 null
    private String awayPrimaryColor;
    private String awayNumberColor;

    private String refereeName;  // 국가가 있으면 "이름 (국가)", 없으면 이름만 반환. 한글 없으면 영문 이름 fallback

}
