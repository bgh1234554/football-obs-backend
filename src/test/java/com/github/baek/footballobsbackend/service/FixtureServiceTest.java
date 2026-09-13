package com.github.baek.footballobsbackend.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class FixtureServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void resolveTeamColorsRunsFallbackThenPromotesNumberWhenPrimaryMissing() throws Exception {
        FixtureService fixtureService = new FixtureService(null, null, null);
        JsonNode colors = objectMapper.readTree("""
                {
                  "primary": null,
                  "number": "ffffff",
                  "border": null
                }
                """);

        Object teamColors = ReflectionTestUtils.invokeMethod(fixtureService, "resolveTeamColors", colors);

        assertThat(readAccessor(teamColors, "primary")).isEqualTo("ffffff");
        assertThat(readAccessor(teamColors, "number")).isEqualTo("000000");
    }

    // WCAG 대비 공식상 max(검정 대비, 흰색 대비)는 어떤 색이든 항상 4.58 이상이라
    // (두 대비 곡선이 만나는 최솟값 지점), MIN_TEXT_CONTRAST_RATIO(3.0)를 절대 못 밑돈다 —
    // 즉 bestReadableTextColor의 보색 반환 분기는 정상적인 hex 입력으로는 도달 불가능한
    // 방어 코드다. 이 테스트는 원래(검정/흰색 우선 단계가 추가되기 전) "번호 색 후보가 없으면
    // 보색을 쓴다"는 동작을 검증했지만, 지금은 primary가 어두운 색이면 흰색(대비 12.7)을
    // 먼저 채택하므로 기대값을 실제 동작(흰색)에 맞게 갱신했다.
    @Test
    void resolveTeamColorsUsesWhiteWhenNoNumberCandidateRemainsAndPrimaryIsDark() throws Exception {
        FixtureService fixtureService = new FixtureService(null, null, null);
        JsonNode colors = objectMapper.readTree("""
                {
                  "primary": "123456",
                  "number": null,
                  "border": null
                }
                """);

        Object teamColors = ReflectionTestUtils.invokeMethod(fixtureService, "resolveTeamColors", colors);

        assertThat(readAccessor(teamColors, "primary")).isEqualTo("123456");
        assertThat(readAccessor(teamColors, "number")).isEqualTo("ffffff");
    }

    @Test
    void resolveTeamColorsKeepsPrimaryAndUsesBorderWhenNumberIsTooSimilar() throws Exception {
        FixtureService fixtureService = new FixtureService(null, null, null);
        JsonNode colors = objectMapper.readTree("""
                {
                  "primary": "ffffff",
                  "number": "fffffe",
                  "border": "000000"
                }
                """);

        Object teamColors = ReflectionTestUtils.invokeMethod(fixtureService, "resolveTeamColors", colors);

        assertThat(readAccessor(teamColors, "primary")).isEqualTo("ffffff");
        assertThat(readAccessor(teamColors, "number")).isEqualTo("000000");
    }

    @Test
    void resolveTeamColorsUsesBorderBeforeNumberMissingComplement() throws Exception {
        FixtureService fixtureService = new FixtureService(null, null, null);
        JsonNode colors = objectMapper.readTree("""
                {
                  "primary": "123456",
                  "number": null,
                  "border": "ffffff"
                }
                """);

        Object teamColors = ReflectionTestUtils.invokeMethod(fixtureService, "resolveTeamColors", colors);

        assertThat(readAccessor(teamColors, "primary")).isEqualTo("123456");
        assertThat(readAccessor(teamColors, "number")).isEqualTo("ffffff");
    }

    @Test
    void resolveTeamColorsUsesPrimaryComplementWhenNumberAndBorderAreTooSimilar() throws Exception {
        FixtureService fixtureService = new FixtureService(null, null, null);
        JsonNode colors = objectMapper.readTree("""
                {
                  "primary": "ffffff",
                  "number": "fffffe",
                  "border": "fffffd"
                }
                """);

        Object teamColors = ReflectionTestUtils.invokeMethod(fixtureService, "resolveTeamColors", colors);

        assertThat(readAccessor(teamColors, "primary")).isEqualTo("ffffff");
        assertThat(readAccessor(teamColors, "number")).isEqualTo("000000");
    }

    private String readAccessor(Object target, String accessor) throws Exception {
        Method method = target.getClass().getDeclaredMethod(accessor);
        method.setAccessible(true);
        return (String) method.invoke(target);
    }
}