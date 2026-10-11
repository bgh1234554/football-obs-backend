package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.dto.fixtures.TeamFixtureDto;
import com.github.baek.footballobsbackend.dto.fixtures.TeamFixturesResponseDto;
import com.github.baek.footballobsbackend.error.ApiException;
import com.github.baek.footballobsbackend.error.ErrorCode;
import com.github.baek.footballobsbackend.util.KoResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

@Service
@RequiredArgsConstructor
public class TeamFixturesService {
    private final ApiFootballClient apiClient;
    private final KoResolver koResolver;

    public TeamFixturesResponseDto getTeamFixtures(long teamId, boolean upcoming) {
        JsonNode entries = apiClient.getTeamFixtures(teamId, upcoming);
        if (entries == null || !entries.isArray()) {
            throw new ApiException(ErrorCode.UPSTREAM_API_ERROR);
        }
        List<TeamFixtureDto> matches = new ArrayList<>();
        entries.forEach(entry -> matches.add(buildMatch(entry)));
        // 오프셋이 다른 날짜도 실제 경기 시각을 기준으로 정렬한다. 미정 일시는 뒤로 보낸다.
        Comparator<TeamFixtureDto> byDate = Comparator.comparing(
                match -> match.getDate() == null ? null : OffsetDateTime.parse(match.getDate()).toInstant(),
                Comparator.nullsLast(upcoming ? Comparator.<Instant>naturalOrder() : Comparator.<Instant>reverseOrder()));
        return new TeamFixturesResponseDto(teamId, matches.stream().sorted(byDate).limit(upcoming ? 5 : 6).toList());
    }

    private TeamFixtureDto buildMatch(JsonNode entry) {
        JsonNode fixture = entry.path("fixture");
        JsonNode home = entry.path("teams").path("home");
        JsonNode away = entry.path("teams").path("away");
        long homeId = home.path("id").asLong();
        long awayId = away.path("id").asLong();
        String homeName = home.path("name").asText(null);
        String awayName = away.path("name").asText(null);
        JsonNode goals = entry.path("goals");
        JsonNode penalty = entry.path("score").path("penalty");
        return TeamFixtureDto.builder()
                .fixtureId(fixture.path("id").asLong())
                .date(fixture.path("date").asText(null))
                .status(fixture.path("status").path("short").asText(null))
                .homeTeamId(homeId)
                .homeTeamName(koResolver.resolveTeamName(homeId, homeName))
                .homeTeamNameShort(koResolver.resolveTeamNameShort(homeId, homeName))
                .homeTeamLogo(koResolver.resolveLogoUrl(homeId, home.path("logo").asText(null), homeName))
                .homeScore(nullableScore(goals.path("home")))
                .homePenaltyScore(nullableScore(penalty.path("home")))
                .awayTeamId(awayId)
                .awayTeamName(koResolver.resolveTeamName(awayId, awayName))
                .awayTeamNameShort(koResolver.resolveTeamNameShort(awayId, awayName))
                .awayTeamLogo(koResolver.resolveLogoUrl(awayId, away.path("logo").asText(null), awayName))
                .awayScore(nullableScore(goals.path("away")))
                .awayPenaltyScore(nullableScore(penalty.path("away")))
                .build();
    }

    private Integer nullableScore(JsonNode node) {
        return node.isNumber() ? node.intValue() : null;
    }
}
