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

    @Test
    void resolveTeamColorsUsesPrimaryComplementWhenNoNumberCandidateRemains() throws Exception {
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
        assertThat(readAccessor(teamColors, "number")).isEqualTo("edcba9");
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