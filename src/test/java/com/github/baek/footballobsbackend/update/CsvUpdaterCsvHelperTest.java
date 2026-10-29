package com.github.baek.footballobsbackend.update;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class CsvUpdaterCsvHelperTest {
    @org.junit.jupiter.api.io.TempDir
    java.nio.file.Path directory;

    @Test
    void quotedValuesSurviveLookupAndRepeatedOverwrite() throws Exception {
        var file = directory.resolve("teams.csv");
        java.nio.file.Files.writeString(file,
                "id,name,city,optional\n\"42\",\"A \"\"B\"\", C\",\"Washington, DC\",\n");
        String[] expected = {"42", "A \"B\", C", "Washington, DC", ""};
        for (int run = 0; run < 2; run++) {
            var table = CsvUpdaterCsvHelper.loadCsvTable(file.toString(), 4);
            assertThat(table.rows().getFirst()).containsExactly(expected);
            assertThat(CsvUpdaterCsvHelper.loadLongIds(file.toString())).containsExactly(42L);
            assertThat(CsvUpdaterCsvHelper.loadIdToColumn(file.toString(), 1, 4)).containsEntry(42L, expected[1]);
            assertThat(CsvUpdaterCsvHelper.loadKeyToColumn(file.toString(), 1, 2, 4))
                    .containsEntry(expected[1].toLowerCase(), expected[2]);
            assertThat(CsvUpdaterCsvHelper.loadVenueNames(file.toString())).contains(expected[1].toLowerCase());
            CsvUpdaterCsvHelper.overwriteCsv(file.toString(), table.header(), table.rows());
        }
    }

    @Test
    void updaterOverridesWebLimitsBeforeLimiterCreation() {
        try (var context = new org.springframework.context.support.GenericApplicationContext()) {
            context.getEnvironment().getPropertySources().addFirst(
                    new org.springframework.core.env.MapPropertySource("web", java.util.Map.of(
                            "rate-limit.upstream.ip-capacity", 1L,
                            "rate-limit.upstream.global-capacity", 1L)));
            CsvUpdater.configureRateLimits(context);
            var env = context.getEnvironment();
            var limiter = new com.github.baek.footballobsbackend.config.UpstreamRateLimiter(
                    env.getRequiredProperty("rate-limit.upstream.ip-capacity", Long.class),
                    env.getRequiredProperty("rate-limit.upstream.ip-refill-period-ms", Long.class),
                    env.getRequiredProperty("rate-limit.upstream.global-capacity", Long.class),
                    env.getRequiredProperty("rate-limit.upstream.global-refill-period-ms", Long.class));
            for (int call = 0; call < 100; call++) limiter.acquire();
        }
    }

    @Test
    void decodesBeforeCsvQuoting() {
        assertThat(CsvUpdaterCsvHelper.esc("W. O&apos;Hora")).isEqualTo("W. O'Hora");
        assertThat(CsvUpdaterCsvHelper.esc("A &quot;B&quot;, C"))
                .isEqualTo("\"A \"\"B\"\", C\"");
        assertThat(CsvUpdaterCsvHelper.esc(null)).isEmpty();
    }
}
