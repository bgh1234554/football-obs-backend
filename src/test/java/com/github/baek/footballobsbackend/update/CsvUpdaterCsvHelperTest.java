package com.github.baek.footballobsbackend.update;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CsvUpdaterCsvHelperTest {
    @Test
    void decodesBeforeCsvQuoting() {
        assertThat(CsvUpdaterCsvHelper.esc("W. O&apos;Hora")).isEqualTo("W. O'Hora");
        assertThat(CsvUpdaterCsvHelper.esc("A &quot;B&quot;, C"))
                .isEqualTo("\"A \"\"B\"\", C\"");
        assertThat(CsvUpdaterCsvHelper.esc(null)).isEmpty();
    }
}
