package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.NormalizedName;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exact matching of the normalised name against the compliance-owned list
 * tables; the real SQL runs against PostgreSQL in ComplianceServiceIT. This is
 * the minimum the NameScreeningPort allows; a vendor screening adapter can
 * replace it behind the same port.
 */
class JdbcNameScreeningAdapterTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final JdbcNameScreeningAdapter adapter = new JdbcNameScreeningAdapter(jdbc);

    @Test
    @SuppressWarnings("unchecked")
    void eachListReadsItsOwnTableAndReportsEverySourceLoad() throws Exception {
        Instant loaded = Instant.parse("2026-10-18T01:00:00Z");
        ResultSet row = mock(ResultSet.class);
        when(row.getString("list_source")).thenReturn("UN");
        when(row.getString("list_version")).thenReturn("2026-10-17");
        when(row.getObject("loaded_at", OffsetDateTime.class)).thenReturn(loaded.atOffset(ZoneOffset.UTC));
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        when(jdbc.query(sql.capture(), any(RowMapper.class))).thenAnswer(call -> List.of(((RowMapper<ListVersion>) call.getArgument(1)).mapRow(row, 0)));
        when(jdbc.queryForObject(contains("sanctions_list_entry"), eq(Boolean.class), eq("TEST SANCTIONED PERSON"))).thenReturn(true);

        ScreeningListMatch match = adapter.match(ScreeningList.SANCTIONS, NormalizedName.of("Test Sanctioned Pérson"));

        assertThat(match.hit()).isTrue();
        assertThat(match.sources()).containsExactly(new ListVersion(ScreeningList.SANCTIONS, "UN", "2026-10-17", loaded));
        assertThat(sql.getValue()).contains("sc_cmp_evidence.sanctions_list_entry").contains("group by list_source, list_version");

        when(jdbc.queryForObject(contains("pep_register_entry"), eq(Boolean.class), eq("TEST SANCTIONED PERSON"))).thenReturn(false);
        ScreeningListMatch pep = adapter.match(ScreeningList.PEP, NormalizedName.of("Test Sanctioned Pérson"));
        assertThat(pep.hit()).isFalse();
        assertThat(pep.sources()).extracting(ListVersion::list).containsOnly(ScreeningList.PEP);
        assertThat(sql.getValue()).contains("sc_cmp_evidence.pep_register_entry");
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEmptyTableIsReportedAsNoSourceAndNoHit() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), anyString())).thenReturn(false);

        ScreeningListMatch match = adapter.match(ScreeningList.PEP, NormalizedName.of("Test Clean Person"));

        assertThat(match).isEqualTo(ScreeningListMatch.none());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aDatabaseFailureIsASourceOutageNotAPass() {
        when(jdbc.query(anyString(), any(RowMapper.class))).thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> adapter.match(ScreeningList.SANCTIONS, NormalizedName.of("Test Clean Person")))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The sanctions list could not be read");
    }
}
