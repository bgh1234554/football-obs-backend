package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.dto.hth.HthMatchDto;
import com.github.baek.footballobsbackend.dto.hth.HthResponseDto;
import com.github.baek.footballobsbackend.util.KoResolver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 두 팀 간 상대 전적(Head-to-Head)을 조립 서비스.
 *
 * [전체 흐름]
 * 1. ApiFootballClient → JsonNode 배열 (BunnyCDN 경유 /fixtures/headtohead 호출)
 * 2. KoResolver → 한글 이름 / CDN URL 적용
 * 3. 각 경기 항목을 HthMatchDto로 변환 후 HthResponseDto로 반환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class HeadtoheadService {

    private final ApiFootballClient apiClient;
    private final KoResolver koResolver;

    // ──────────────────────────────────────────────
    // Public API
    // ──────────────────────────────────────────────

    /**
     * 두 팀 ID를 받아 상대 전적 목록을 반환.
     * 최신 경기가 앞으로 오도록 date 내림차순 정렬.
     */
    public HthResponseDto getHeadtoHead(long teamA, long teamB) {
        JsonNode entries = apiClient.getHeadtoHeadRecord(teamA, teamB);
        if (entries == null || !entries.isArray()) {
            return HthResponseDto.builder().matches(List.of()).build();
        }

        List<HthMatchDto> matches = new ArrayList<>();
        for (JsonNode entry : entries) {
            matches.add(buildMatch(entry));
        }
        matches.sort(Comparator.comparing(HthMatchDto::getDate).reversed());

        return HthResponseDto.builder().matches(matches).build();
    }

    // ──────────────────────────────────────────────
    // 경기 항목 조립
    // ──────────────────────────────────────────────

    private HthMatchDto buildMatch(JsonNode entry) {
        JsonNode fixture   = entry.path("fixture");
        JsonNode league    = entry.path("league");
        JsonNode goals     = entry.path("goals");
        JsonNode score     = entry.path("score");
        JsonNode teamsNode = entry.path("teams");

        // 1. 리그 이름/로고 한글화
        long leagueId        = league.path("id").asLong();
        String leagueApiName = league.path("name").asText();

        // 2. 홈/원정 팀 파싱
        JsonNode homeTeam  = teamsNode.path("home");
        JsonNode awayTeam  = teamsNode.path("away");
        long homeTeamId    = homeTeam.path("id").asLong();
        long awayTeamId    = awayTeam.path("id").asLong();
        String homeApiName = homeTeam.path("name").asText();
        String awayApiName = awayTeam.path("name").asText();

        // 3. 경기장 이름/도시 한글화
        JsonNode venueNode = fixture.path("venue");

        // 4. 페널티 슛아웃 점수 추출 — null이면 PSO 없는 경기
        JsonNode penaltyNode     = score.path("penalty");
        Integer homePenaltyScore = penaltyNode.path("home").isNull() ? null : penaltyNode.path("home").asInt();
        Integer awayPenaltyScore = penaltyNode.path("away").isNull() ? null : penaltyNode.path("away").asInt();

        // 5. DTO 조립
        return HthMatchDto.builder()
                .fixtureId(fixture.path("id").asLong())
                .date(fixture.path("date").asText(null))
                .leagueName(koResolver.resolveLeagueName(leagueId, leagueApiName))
                .leagueLogoUrl(koResolver.resolveLeagueLogoUrl((int) leagueId, league.path("logo").asText()))
                .season(league.path("season").asInt())
                .leagueRound(league.path("round").asText(null))
                .refereeName(koResolver.buildRefereeName(fixture.path("referee").asText(null)))
                .venueName(koResolver.resolveVenueName(venueNode))
                .venueCity(koResolver.resolveVenueCity(venueNode))
                .homeTeamId(homeTeamId)
                .homeTeamName(koResolver.resolveTeamName(homeTeamId, homeApiName))
                .homeTeamLogo(koResolver.resolveLogoUrl(homeTeamId, homeTeam.path("logo").asText(), homeApiName))
                .homeScore(goals.path("home").asInt())
                .homePenaltyScore(homePenaltyScore)
                .awayTeamId(awayTeamId)
                .awayTeamName(koResolver.resolveTeamName(awayTeamId, awayApiName))
                .awayTeamLogo(koResolver.resolveLogoUrl(awayTeamId, awayTeam.path("logo").asText(), awayApiName))
                .awayScore(goals.path("away").asInt())
                .awayPenaltyScore(awayPenaltyScore)
                .build();
    }
}
