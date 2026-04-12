package com.github.baek.footballobsbackend.dto.hth;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 상대 전적 목록의 경기 한 건을 나타내는 DTO.
 *
 * [포함 정보]
 * - 대회: leagueName, leagueLogoUrl, season, leagueRound
 * - 날짜: date (ISO-8601)
 * - 주심: refereeName (한글 우선)
 * - 경기장: venueName, venueCity (한글 우선)
 * - 홈팀: homeTeamId, homeTeamName, homeTeamLogo, homeWinner, homeScore, homePenaltyScore
 * - 원정팀: awayTeamId, awayTeamName, awayTeamLogo, awayWinner, awayScore, awayPenaltyScore
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HthMatchDto {

    // ── 경기 식별 ────────────────────────────────────────────────
    long fixtureId;
    /** ISO-8601 날짜 문자열. ex) "2024-10-06T16:30:00+00:00" */
    String date;

    // ── 대회 ─────────────────────────────────────────────────────
    /** 리그 한글명 우선. 없으면 API 영문명. */
    String leagueName;
    /** leagues.csv 커스텀 URL 우선, 없으면 Media CDN 치환 URL. */
    String leagueLogoUrl;
    /** 시즌 연도. ex) 2024 */
    int season;
    /** 라운드 문자열. ex) "Regular Season - 8" */
    String leagueRound;

    // ── 주심 / 경기장 ────────────────────────────────────────────
    /** "한글이름 (국가)" 형식. 한글 없으면 영문. 정보 없으면 null. */
    String refereeName;
    /** 경기장 한글명 우선. 없으면 API 영문명. */
    String venueName;
    /** 도시 한글명 우선. 없으면 API 영문명. */
    String venueCity;

    // ── 홈팀 ─────────────────────────────────────────────────────
    long homeTeamId;
    /** 팀 한글 풀네임 우선. 없으면 API 영문명. */
    String homeTeamName;
    /** 팀 로고 URL (logos.csv 커스텀 → Media CDN 치환 순). */
    String homeTeamLogo;
    /** 정규+연장 득점 합계 (goals 필드 기준). */
    int homeScore;
    /** 페널티 슛아웃 점수. 페널티 없는 경기는 null. */
    Integer homePenaltyScore;

    // ── 원정팀 ───────────────────────────────────────────────────
    long awayTeamId;
    String awayTeamName;
    String awayTeamLogo;
    int awayScore;
    Integer awayPenaltyScore;
}
