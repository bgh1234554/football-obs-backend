package com.github.baek.footballobsbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * teams.csv에 새로 채운 primary_color_override/number_color_override가
 * 1) team_id로 바로 조회됐을 때, 2) 연령대/여자부 접미사(U22, W 등)가 붙은 팀이
 * teamId로는 못 찾고 기준 팀명으로 fallback했을 때 — 실제로 정확히 응답 흐름에
 * 쓰이는 CsvLoader.getTeamColorOverride / KoResolver.resolveTeamName(Short)을
 * 그대로 호출해 값이 올바르게 나오는지 확인한다.
 *
 * FixtureService.buildMatchInfo가 MatchInfoDto.homePrimaryColor/homeNumberColor/
 * homeTeamName/homeTeamNameShort를 채울 때 실제로 부르는 메서드와 동일하므로,
 * 여기서 통과하면 응답 바디에도 동일하게 반영된다.
 */
class CsvLoaderTeamColorTest {

    private static CsvLoader csvLoader;
    private static KoResolver koResolver;

    @BeforeAll
    static void setUp() {
        csvLoader = new CsvLoader();
        csvLoader.load();
        koResolver = new KoResolver(csvLoader);
    }

    @Test
    void directTeamIdLookupReturnsPdfExtractedColor() {
        // SCR 알타흐(오스트리아 분데스리가) - 이번에 PDF에서 새로 채운 팀
        String[] override = csvLoader.getTeamColorOverride(618L);

        assertThat(override).isNotNull();
        assertThat(override[0]).isEqualTo("ffd400");
        assertThat(override[1]).isEqualTo("000000");
    }

    @Test
    void ageGroupSuffixFallsBackToBaseTeamColor() {
        // South Korea(17)는 색이 있지만 "South Korea U22"라는 team_id는 teams.csv에 없음
        // -> apiName 접미사를 떼고 기준 팀(17)의 색을 그대로 물려받아야 함
        String[] override = csvLoader.getTeamColorOverride(999999L, "South Korea U22");

        assertThat(override).isNotNull();
        assertThat(override[0]).isEqualTo("e6002d");
        assertThat(override[1]).isEqualTo("021858");
    }

    @Test
    void ageGroupSuffixWithWomenMarkerAlsoFallsBack() {
        String[] override = csvLoader.getTeamColorOverride(999999L, "South Korea U17 W");

        assertThat(override).isNotNull();
        assertThat(override[0]).isEqualTo("e6002d");
        assertThat(override[1]).isEqualTo("021858");
    }

    @Test
    void teamWithNoOverrideAndNoBaseMatchReturnsNull() {
        String[] override = csvLoader.getTeamColorOverride(999999L, "Definitely Not A Real Team U22");

        assertThat(override).isNull();
    }

    @Test
    void resolveTeamNameFallsBackToBaseKoreanNameForAgeGroupVariant() {
        String name = koResolver.resolveTeamName(999999L, "South Korea U22");

        assertThat(name).isEqualTo("대한민국");
    }

    @Test
    void resolveTeamNameShortFallsBackToBaseKoreanShortNameForAgeGroupVariant() {
        String name = koResolver.resolveTeamNameShort(999999L, "South Korea W");

        assertThat(name).isEqualTo("대한민국");
    }
}
