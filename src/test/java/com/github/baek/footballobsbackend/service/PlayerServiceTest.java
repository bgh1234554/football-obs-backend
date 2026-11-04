package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerSeasonStatDto;
import com.github.baek.footballobsbackend.util.CsvLoader;
import com.github.baek.footballobsbackend.util.KoResolver;
import com.github.baek.footballobsbackend.client.ApiFootballClient;
import com.github.baek.footballobsbackend.dto.stats.PlayerProfileStatResponseDto;
import com.github.baek.footballobsbackend.error.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PlayerServiceTest {

    @Test
    void buildSeasonStatsUsesZeroLeagueIdAndNameFallbackWhenApiLeagueIdIsNull() throws Exception {
        CsvLoader csvLoader = new CsvLoader();
        csvLoader.load();
        KoResolver koResolver = new KoResolver(csvLoader);
        PlayerService playerService = new PlayerService(null, csvLoader, koResolver);

        JsonNode statsNode = new ObjectMapper().readTree("""
                [
                  {
                    "team": {
                      "id": 999999,
                      "name": "Test Team",
                      "logo": null
                    },
                    "league": {
                      "id": null,
                      "name": "Friendlies",
                      "logo": null,
                      "season": 2026
                    }
                  },
                  {
                    "team": {
                      "id": 999999,
                      "name": "Test Team",
                      "logo": null
                    },
                    "league": {
                      "id": null,
                      "name": "Unknown Cup",
                      "logo": null,
                      "season": 2026
                    }
                  },
                  {
                    "team": {
                      "id": 999999,
                      "name": "Test Team",
                      "logo": null
                    },
                    "league": {
                      "id": 10,
                      "name": "Friendlies",
                      "logo": null,
                      "season": 2026
                    }
                  },
                  {
                    "team": {
                      "id": 999999,
                      "name": "Test Team",
                      "logo": null
                    },
                    "league": {
                      "id": 39,
                      "name": "Premier League",
                      "logo": null,
                      "season": 2026
                    }
                  }
                ]
                """);

        @SuppressWarnings("unchecked")
        List<PlayerSeasonStatDto> stats = ReflectionTestUtils.invokeMethod(
                playerService,
                "buildSeasonStats",
                statsNode
        );

        assertThat(stats).hasSize(4);
        assertThat(stats).extracting(stat -> stat.getLeague().getId()).containsExactly(39, 0, 10, 0);
        assertThat(stats.get(3).getLeague().getName()).isNotEqualTo("Friendlies");
    }

    @Test
    void getPlayerSeasonStatsSkipsEmptySeasonsWithinLookbackWithoutProfileFallback() throws Exception {
        CsvLoader csvLoader = new CsvLoader();
        csvLoader.load();
        ApiFootballClient apiClient = mock(ApiFootballClient.class);
        PlayerService playerService = new PlayerService(apiClient, csvLoader, new KoResolver(csvLoader));

        // 2021~2020은 기록 없음(무소속 등), 2019에 기록, 그 이전은 없음.
        JsonNode emptyArray = new ObjectMapper().readTree("[]");
        when(apiClient.getPlayerStats(eq(7L), anyInt())).thenReturn(emptyArray);
        when(apiClient.getPlayerStats(7L, 2019)).thenReturn(new ObjectMapper().readTree("""
                [{"player": {"id": 7, "name": "Test Player"},
                  "statistics": [{"team": {"id": 999999, "name": "Test Team"},
                                  "league": {"id": 39, "name": "Premier League", "season": 2019}}]}]
                """));

        PlayerProfileStatResponseDto found = playerService.getPlayerSeasonStats(7L, 2021);
        assertThat(found.getStatistics()).containsOnlyKeys("2019");
        assertThat(found.getStatistics().get("2019")).hasSize(1);

        // 2018부터 3개 연도(2018~2016)가 모두 비면 더 내려가지 않고 빈 statistics.
        PlayerProfileStatResponseDto empty = playerService.getPlayerSeasonStats(7L, 2018);
        assertThat(empty.getStatistics()).isEmpty();
        verify(apiClient, never()).getPlayerStats(7L, 2015);
        verify(apiClient, never()).getPlayerProfile(anyLong());

        assertThatThrownBy(() -> playerService.getPlayerSeasonStats(7L, 1989)).isInstanceOf(ApiException.class);
        verify(apiClient, never()).getPlayerStats(anyLong(), eq(1989));
    }
}
