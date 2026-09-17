package com.github.baek.footballobsbackend.util;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PersonNameFormatterTest {
    @Test
    void decodesEntitiesBeforeReturningAnExistingShortName() {
        assertThat(PersonNameFormatter.buildNameShortAbbrev("W. O&apos;Hora", "Warren", "O'Hora", "Republic of Ireland"))
                .isEqualTo("W. O'Hora");
        assertThat(PersonNameFormatter.buildNameShortAbbrev("T. O&amp;apos;Reilly", "", "", "England"))
                .isEqualTo("T. O'Reilly");
    }

    @Test
    void decodesNumericEntitiesAndPreservesAccents() {
        assertThat(PersonNameFormatter.buildLongName("Tommi", "O&#39;Reilly", "England"))
                .isEqualTo("Tommi O'Reilly");
        assertThat(PersonNameFormatter.buildLongName("Malick", "N&#x27;Diaye", "France"))
                .isEqualTo("Malick N'Diaye");
        assertThat(PersonNameFormatter.decodeHtmlEntities("B. Andr&eacute;sson"))
                .isEqualTo("B. Andrésson");
    }

    @Test
    void preservesNicknameAndKoreanInitialsRules() {
        assertThat(PersonNameFormatter.buildNameShortAbbrev("Vitinha", "Vítor", "Ferreira", "Portugal"))
                .isEqualTo("Vitinha");
        assertThat(PersonNameFormatter.buildNameShortAbbrev("Son Heung-Min", "Heung-Min", "Son", "Korea Republic"))
                .isEqualTo("Son H.M.");
        assertThat(PersonNameFormatter.decodeHtmlEntities(null)).isEmpty();
        assertThat(PersonNameFormatter.decodeHtmlEntities("O'Hora & unknown;"))
                .isEqualTo("O'Hora & unknown;");
    }
}
