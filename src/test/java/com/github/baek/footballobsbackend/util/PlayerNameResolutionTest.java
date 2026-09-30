package com.github.baek.footballobsbackend.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.baek.footballobsbackend.dto.fixtures.Layer1.Layer2.PlayerDto;
import com.github.baek.footballobsbackend.service.FixtureService;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PlayerNameResolutionTest {
    private static CsvLoader csv;
    private static KoResolver resolver;

    @BeforeAll
    static void loadRealPlayerNames() {
        csv = new CsvLoader();
        csv.load();
        resolver = new KoResolver(csv);
    }

    @ParameterizedTest
    @ValueSource(strings = {"J. Mickels", "J Mickels", "j.   mickels"})
    void missingIdAndAbbreviationNeverBorrowAnotherPlayersName(String name) {
        assertThat(csv.getPlayerRowByName(name)).isNull();
        var result = resolver.resolvePlayerName(0, name);
        assertThat(result.displayName()).doesNotContain("미켈스");
        assertThat(result.longName()).isIn(null, name);
    }

    @Test
    void knownIdStillUsesItsTranslation() {
        var result = resolver.resolvePlayerName(127602, "J. Mickels");
        assertThat(result.displayName()).isEqualTo("J. 미켈스");
        assertThat(result.longName()).isEqualTo("조이랜스 미켈스");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Joy-Lance Mickels", "Joy Lance Mickels", "  JOY   LANCE MICKELS  "})
    void uniqueFullNameAllowsFormattingDifferences(String name) {
        assertThat(resolver.resolvePlayerName(0, name).longName()).isEqualTo("조이랜스 미켈스");
    }

    @Test
    void differentMiddleNameIsNotDroppedToForceAMatch() {
        var result = resolver.resolvePlayerName(0, "Joy Sloyd Mickels");
        assertThat(csv.getPlayerRowByName("Joy Sloyd Mickels")).isNull();
        assertThat(result.displayName()).doesNotContain("미켈스");
        assertThat(result.longName()).isEqualTo("Joy Sloyd Mickels");
    }

    @Test
    void duplicateFullNamesStayAmbiguousAfterFurtherRows() {
        var loader = new CsvLoader();
        for (String id : List.of("1", "2", "3")) {
            ReflectionTestUtils.invokeMethod(loader, "indexPlayerFullName",
                    (Object) new String[]{id, "S. Person", "Same Person"});
        }
        assertThat(loader.getPlayerRowByName("Same Person")).isNull();
    }

    @Test
    void repeatedRowOfTheSameIdDoesNotCreateFalseAmbiguity() {
        var loader = new CsvLoader();
        String[] row = {"1", "S. Person", "Same Person"};
        ReflectionTestUtils.invokeMethod(loader, "indexPlayerFullName", (Object) row);
        ReflectionTestUtils.invokeMethod(loader, "indexPlayerFullName", (Object) row);
        assertThat(loader.getPlayerRowByName("Same Person")).isEqualTo(row);
    }

    @Test
    void fixtureKeepsBothMickelsPlayersWithIndependentNames() throws Exception {
        var service = new FixtureService(null, csv, resolver);
        var rows = new ObjectMapper().readTree("""
                [
                  {"player":{"id":127602,"name":"J. Mickels","number":20,"pos":"M","grid":"4:2"}},
                  {"player":{"id":null,"name":"J. Mickels","number":9,"pos":"F","grid":null}}
                ]
                """);
        List<PlayerDto> players = ReflectionTestUtils.invokeMethod(service, "buildPlayerList", rows, Map.of());
        assertThat(players).hasSize(2);
        assertThat(players.get(0).getNumber()).isEqualTo(20);
        assertThat(players.get(0).getNameKoLong()).isEqualTo("조이랜스 미켈스");
        assertThat(players.get(1).getNumber()).isEqualTo(9);
        assertThat(players.get(1).getName()).isEqualTo("J. Mickels");
        assertThat(players.get(1).getNameKoLong()).isNull();
        assertThat(players.get(1).getOrigName()).isEqualTo("J. Mickels");
    }
}
