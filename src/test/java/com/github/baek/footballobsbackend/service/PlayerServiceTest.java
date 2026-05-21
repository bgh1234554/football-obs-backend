package com.github.baek.footballobsbackend.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.dto.stats.Layer1.PlayerSeasonStatDto;
import com.github.baek.footballobsbackend.util.CsvLoader;
import com.github.baek.footballobsbackend.util.KoResolver;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

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
}
