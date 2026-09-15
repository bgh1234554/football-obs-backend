package com.github.baek.footballobsbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class TeamLogoFallbackTest {

    private CsvLoader csvLoader;
    private KoResolver koResolver;

    @BeforeEach
    void setUp() {
        csvLoader = new CsvLoader();
        Map<String, String[]> teamsByName = new HashMap<>();
        teamsByName.put("south korea", new String[]{"17", "South Korea", "대한민국", "대한민국"});
        teamsByName.put("no logo", new String[]{"18", "No Logo"});
        teamsByName.put("no id", new String[]{"", "No Id"});
        ReflectionTestUtils.setField(csvLoader, "teamsByName", teamsByName);
        Map<Long, String[]> logos = new HashMap<>();
        logos.put(17L, new String[]{"17", "대한민국", "https://example.com/kr.svg", "https://example.com/kfa.svg"});
        logos.put(100L, new String[]{"100", "대한민국 U23", "https://example.com/u23.svg", "https://example.com/u23-fa.svg"});
        logos.put(101L, new String[]{"101", "대한민국 W", " ", " "});
        ReflectionTestUtils.setField(csvLoader, "logos", logos);
        koResolver = new KoResolver(csvLoader);
        ReflectionTestUtils.setField(koResolver, "mediaCdnUrl", "https://cdn.example.com");
    }

    @ParameterizedTest
    @ValueSource(strings = {"South Korea U23", "South Korea U-23", "South Korea W",
            "South Korea U17 W", "South Korea U-17 W", " south korea   u-23 w "})
    void variantsInheritBaseTeamNameLogoAndAssociationLogo(String apiName) {
        assertThat(koResolver.resolveTeamName(999L, apiName)).isEqualTo("대한민국");
        assertThat(koResolver.resolveLogoUrl(999L, null, apiName)).isEqualTo("https://example.com/kr.svg");
        assertThat(csvLoader.getFaUrl(999L, apiName)).isEqualTo("https://example.com/kfa.svg");
    }

    @Test
    void variantSpecificLogosTakePriority() {
        assertThat(koResolver.resolveLogoUrl(100L, null, "South Korea U23"))
                .isEqualTo("https://example.com/u23.svg");
        assertThat(csvLoader.getFaUrl(100L, "South Korea U23"))
                .isEqualTo("https://example.com/u23-fa.svg");
    }

    @Test
    void blankVariantLogosFallBackToBaseTeam() {
        assertThat(koResolver.resolveLogoUrl(101L, null, "South Korea W"))
                .isEqualTo("https://example.com/kr.svg");
        assertThat(csvLoader.getFaUrl(101L, "South Korea W"))
                .isEqualTo("https://example.com/kfa.svg");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"Unknown U-23", "No Logo W", "No Id W", "South Korea", "South Korea United"})
    void missingBaseLogoKeepsApiCdnFallback(String apiName) {
        assertThat(koResolver.resolveLogoUrl(999L, "https://media.api-sports.io/football/teams/999.png", apiName))
                .isEqualTo("https://cdn.example.com/football/teams/999.png");
        assertThat(csvLoader.getFaUrl(999L, apiName)).isNull();
    }

    @Test
    void noCustomOrApiLogoReturnsNull() {
        assertThat(koResolver.resolveLogoUrl(999L, null, "Unknown W")).isNull();
        assertThat(koResolver.resolveLogoUrl(999L, " ", "Unknown W")).isNull();
    }
}
