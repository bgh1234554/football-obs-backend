package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.dto.fixtures.TeamFixtureDto;
import com.github.baek.footballobsbackend.error.ApiException;
import com.github.baek.footballobsbackend.util.KoResolver;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamFixturesServiceTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApiFootballClient client = mock(ApiFootballClient.class);
    private final KoResolver resolver = mock(KoResolver.class);
    private final TeamFixturesService service = new TeamFixturesService(client, resolver);

    private JsonNode sample(String name) throws Exception {
        // 원본 응답 파일 대신 변환에 필요한 필드만 가진 최소 데이터를 사용한다.
        boolean upcoming = name.equals("Next");
        ArrayNode entries = mapper.createArrayNode();
        for (int i = 0; i < (upcoming ? 5 : 6); i++) {
            var entry = mapper.createObjectNode();
            var fixture = entry.putObject("fixture");
            fixture.put("id", (upcoming ? 1635690 : 1552780) + i);
            fixture.put("date", "2026-10-" + "%02d".formatted((upcoming ? 14 : 10) + (upcoming ? i : -i))
                    + "T19:00:00+00:00");
            fixture.putObject("status").put("short", upcoming ? "NS" : "FT");
            var teams = entry.putObject("teams");
            teams.putObject("home").put("id", upcoming ? 50 : 85)
                    .put("name", upcoming ? "Manchester City" : "Paris Saint Germain")
                    .put("logo", "https://media.api-sports.io/football/teams/" + (upcoming ? 50 : 85) + ".png");
            teams.putObject("away").put("id", upcoming ? 85 : 1298 + i)
                    .put("name", upcoming ? "Paris Saint Germain" : i == 0 ? "Le Mans" : "Other Team")
                    .put("logo", "https://media.api-sports.io/football/teams/" + (upcoming ? 85 : 1298 + i) + ".png");
            var goals = entry.putObject("goals");
            if (upcoming) goals.putNull("home").putNull("away");
            else goals.put("home", 3).put("away", 1);
            entry.putObject("score").putObject("penalty").putNull("home").putNull("away");
            entries.add(entry);
        }
        return entries;
    }

    @Test
    void lastSamplePreservesScoresAndResolvesBothTeams() throws Exception {
        when(client.getTeamFixtures(85, false)).thenReturn(sample("Last"));
        when(resolver.resolveTeamName(85, "Paris Saint Germain")).thenReturn("파리 생제르맹");
        when(resolver.resolveTeamNameShort(85, "Paris Saint Germain")).thenReturn("PSG");
        when(resolver.resolveTeamName(1298, "Le Mans")).thenReturn("르망 FC");
        when(resolver.resolveTeamNameShort(1298, "Le Mans")).thenReturn("르망");
        when(resolver.resolveLogoUrl(85, "https://media.api-sports.io/football/teams/85.png",
                "Paris Saint Germain")).thenReturn("https://custom/psg.svg");
        var result = service.getTeamFixtures(85, false);
        assertThat(result.teamId()).isEqualTo(85);
        assertThat(result.matches()).hasSize(6);
        var first = result.matches().getFirst();
        assertThat(first.getFixtureId()).isEqualTo(1552780);
        assertThat(first.getHomeTeamName()).isEqualTo("파리 생제르맹");
        assertThat(first.getHomeTeamNameShort()).isEqualTo("PSG");
        assertThat(first.getAwayTeamName()).isEqualTo("르망 FC");
        assertThat(first.getAwayTeamNameShort()).isEqualTo("르망");
        assertThat(first.getHomeTeamLogo()).isEqualTo("https://custom/psg.svg");
        assertThat(first.getHomeScore()).isEqualTo(3);
        assertThat(first.getAwayScore()).isEqualTo(1);
        assertThat(first.getHomePenaltyScore()).isNull();
        assertThat(first.getStatus()).isEqualTo("FT");
        verify(resolver).resolveTeamName(1298, "Le Mans");
        verify(resolver).resolveLogoUrl(1298, "https://media.api-sports.io/football/teams/1298.png", "Le Mans");
    }

    @Test
    void nextSampleSortsShuffledEntriesAndKeepsUnknownScoresNull() throws Exception {
        ArrayNode shuffled = mapper.createArrayNode();
        JsonNode entries = sample("Next");
        for (int i = entries.size() - 1; i >= 0; i--) shuffled.add(entries.get(i));
        shuffled.add(entries.get(entries.size() - 1).deepCopy());
        when(client.getTeamFixtures(85, true)).thenReturn(shuffled);
        var result = service.getTeamFixtures(85, true);
        assertThat(result.matches()).hasSize(6);
        assertThat(result.matches().getFirst().getFixtureId()).isEqualTo(1635690);
        assertThat(result.matches()).allSatisfy(match -> {
            assertThat(match.getHomeScore()).isNull();
            assertThat(match.getAwayScore()).isNull();
            assertThat(match.getStatus()).isEqualTo("NS");
        });
    }

    @Test
    void keepsZeroAndPenaltyScoresAndMissingScoresDistinct() throws Exception {
        JsonNode entry = sample("Last").get(0).deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) entry.path("goals")).put("home", 0).remove("away");
        ((com.fasterxml.jackson.databind.node.ObjectNode) entry.path("score").path("penalty"))
                .put("home", 4).put("away", 3);
        when(client.getTeamFixtures(85, false)).thenReturn(mapper.createArrayNode().add(entry));
        TeamFixtureDto match = service.getTeamFixtures(85, false).matches().getFirst();
        assertThat(match.getHomeScore()).isZero();
        assertThat(match.getAwayScore()).isNull();
        assertThat(match.getHomePenaltyScore()).isEqualTo(4);
        assertThat(match.getAwayPenaltyScore()).isEqualTo(3);
    }

    @Test
    void emptyListIsSuccessfulButMalformedUpstreamIsAnError() {
        when(client.getTeamFixtures(85, true)).thenReturn(mapper.createArrayNode());
        assertThat(service.getTeamFixtures(85, true).matches()).isEmpty();
        when(client.getTeamFixtures(85, true)).thenReturn(null);
        assertThatThrownBy(() -> service.getTeamFixtures(85, true)).isInstanceOf(ApiException.class);
    }
}
