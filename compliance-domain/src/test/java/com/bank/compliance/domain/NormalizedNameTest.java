package com.bank.compliance.domain;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The name matched against the sanctions and PEP lists: upper case, NFD with
 * every combining mark removed, punctuation turned into spaces, whitespace
 * collapsed. The same rule is applied by the list loader
 * (scripts/ops/normalize-screening-name.py); both are checked against the
 * shared fixtures in scripts/ops/test/screening-name-fixtures.csv, so a
 * customer name and a list entry normalise the same way on both sides.
 */
class NormalizedNameTest {

    @Test
    void upperCasesStripsDiacriticsAndPunctuationAndCollapsesWhitespace() {
        assertThat(NormalizedName.of("Test", "Sanctioned Pérson").value()).isEqualTo("TEST SANCTIONED PERSON");
        assertThat(NormalizedName.of("  tEst   Ünïcode-Näme ").value()).isEqualTo("TEST UNICODE NAME");
        assertThat(NormalizedName.of("Test O'Dummy, Jr.").value()).isEqualTo("TEST O DUMMY JR");
        assertThat(NormalizedName.of("TEST\tTAB\nDUMMY").value()).isEqualTo("TEST TAB DUMMY");
    }

    @Test
    void lettersWithoutADecompositionAreKeptAsTheyAre() {
        // Ł has no canonical decomposition, so NFD leaves it; both sides keep it.
        assertThat(NormalizedName.of("Łukasz Test-Fixture").value()).isEqualTo("ŁUKASZ TEST FIXTURE");
    }

    @Test
    void theSameNameWrittenDifferentlyNormalisesToOneValue() {
        NormalizedName listed = NormalizedName.of("TEST SANCTIONED PERSON");

        assertThat(NormalizedName.of("test", "sanctioned", "person")).isEqualTo(listed);
        assertThat(NormalizedName.of("Tést Sanctioned-Person")).isEqualTo(listed);
        assertThat(NormalizedName.of("Test Sanctioned Person II")).isNotEqualTo(listed);
    }

    @Test
    void aNameThatNormalisesToNothingIsRefused() {
        assertThatThrownBy(() -> NormalizedName.of("  ", "...")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name is required");
        assertThatThrownBy(() -> NormalizedName.of((String) null)).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("name is required");
        assertThatThrownBy(() -> new NormalizedName("lower case")).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("normalised");
    }

    /** The loader's rehearsal (scripts/ops/test/rehearse-screening-list-load.sh) checks the same file in SQL. */
    @Test
    void agreesWithTheLoaderOnTheSharedDiacriticsFixtures() throws IOException {
        List<String> lines = Files.readAllLines(fixtures(), StandardCharsets.UTF_8);
        assertThat(lines.getFirst()).isEqualTo("display_name,expected_normalized");
        assertThat(lines).as("fixtures with diacritics, punctuation and odd spacing").hasSizeGreaterThanOrEqualTo(7);

        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split(",(?=[^,]*$)");
            assertThat(columns).as(line).hasSize(2);
            String displayName = columns[0].replaceAll("^\"|\"$", "");
            assertThat(NormalizedName.of(displayName).value()).as(displayName).isEqualTo(columns[1]);
        }
    }

    private static Path fixtures() throws IOException {
        for (String prefix : List.of("", "../", "../../", "../../../")) {
            Path candidate = Path.of(prefix + "scripts/ops/test/screening-name-fixtures.csv");
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        throw new IOException("Unable to locate scripts/ops/test/screening-name-fixtures.csv");
    }
}
