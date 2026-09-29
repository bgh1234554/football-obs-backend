package com.github.baek.footballobsbackend.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

class MojibakeRepairTest {

    @Test
    void repairsWindows1252Mojibake() {
        assertThat(MojibakeRepair.repair("K. UrbaÅ„ski")).isEqualTo("K. Urbański");
        assertThat(MojibakeRepair.repair("JÃ©rÃ©my Doku")).isEqualTo("Jérémy Doku");
        // Š = C5 A0 → "Å" + NBSP(U+00A0)
        assertThat(MojibakeRepair.repair("Å ime Vrsaljko")).isEqualTo("Šime Vrsaljko");
        assertThat(MojibakeRepair.repair("MÃ¼ller")).isEqualTo("Müller");
    }

    @Test
    void unescapesHtmlEntities() {
        assertThat(MojibakeRepair.repair("Y. Said M&apos;Madi")).isEqualTo("Y. Said M'Madi");
        assertThat(MojibakeRepair.repair("N&#39;Golo Kant&eacute;")).isEqualTo("N'Golo Kanté");
        assertThat(MojibakeRepair.repair("Brighton &amp; Hove Albion")).isEqualTo("Brighton & Hove Albion");
        // 엔티티가 아닌 일반 "&"는 그대로
        assertThat(MojibakeRepair.repair("Brighton & Hove Albion")).isEqualTo("Brighton & Hove Albion");
    }

    @Test
    void repairsDoubleMojibake() {
        String once = "Urbański";
        String twice = new String(new String(once.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.Charset.forName("windows-1252")).getBytes(java.nio.charset.StandardCharsets.UTF_8),
                java.nio.charset.Charset.forName("windows-1252"));
        assertThat(MojibakeRepair.repair(twice)).isEqualTo(once);
    }

    @Test
    void keepsCorrectStrings() {
        assertThat(MojibakeRepair.repair("K. Urbański")).isEqualTo("K. Urbański");
        assertThat(MojibakeRepair.repair("Thomas Müller")).isEqualTo("Thomas Müller");
        assertThat(MojibakeRepair.repair("Melker Åke Ellborg")).isEqualTo("Melker Åke Ellborg");
        assertThat(MojibakeRepair.repair("Łukasz Fabiański")).isEqualTo("Łukasz Fabiański");
        assertThat(MojibakeRepair.repair("São Paulo")).isEqualTo("São Paulo");
        assertThat(MojibakeRepair.repair("흐비차 크바라츠헬리아")).isEqualTo("흐비차 크바라츠헬리아");
        assertThat(MojibakeRepair.repair("Plain ASCII")).isEqualTo("Plain ASCII");
        assertThat(MojibakeRepair.repair(null)).isNull();
    }

    @Test
    void repairsAllTextValuesInTree() throws Exception {
        JsonNode root = new ObjectMapper().readTree("""
                {"lineups":[{"startXI":[{"player":{"id":1,"name":"K. UrbaÅ„ski","pos":"M"}}]}],
                 "fixture":{"referee":"Szymon Marciniak, Poland","venue":{"name":"Stadion Narodowy"}},
                 "tags":["JÃ©rÃ©my", "ok"]}
                """);
        MojibakeRepair.repairTree(root);
        assertThat(root.at("/lineups/0/startXI/0/player/name").asText()).isEqualTo("K. Urbański");
        assertThat(root.at("/lineups/0/startXI/0/player/id").asInt()).isEqualTo(1);
        assertThat(root.at("/fixture/venue/name").asText()).isEqualTo("Stadion Narodowy");
        assertThat(root.at("/tags/0").asText()).isEqualTo("Jérémy");
        assertThat(root.at("/tags/1").asText()).isEqualTo("ok");
    }
}
