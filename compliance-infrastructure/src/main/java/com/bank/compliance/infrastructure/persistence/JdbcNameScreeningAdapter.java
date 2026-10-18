package com.bank.compliance.infrastructure.persistence;

import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.NormalizedName;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import com.bank.compliance.domain.port.out.NameScreeningPort;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/**
 * Exact matching of a normalised name against the compliance-owned list
 * tables sc_cmp_evidence.sanctions_list_entry and pep_register_entry, which
 * the operator script scripts/ops/load-screening-list.sh fills (one load per
 * source: list_source, list_version, loaded_at). No JPA entity: the service
 * only reads these tables (the runtime role has SELECT), so Hibernate has
 * nothing to validate or write.
 *
 * Exact matching on the shared normalisation is the minimum the port allows;
 * a fuzzy or vendor screening adapter can replace this class behind
 * {@link NameScreeningPort}.
 */
@Component
public class JdbcNameScreeningAdapter implements NameScreeningPort {

    private static final String SCHEMA = "sc_cmp_evidence";

    private final JdbcTemplate jdbc;

    public JdbcNameScreeningAdapter(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc is required");
    }

    @Override
    public ScreeningListMatch match(ScreeningList list, NormalizedName name) {
        String table = SCHEMA + "." + tableOf(list);
        try {
            List<ListVersion> sources = jdbc.query(
                    "select list_source, list_version, max(loaded_at) as loaded_at from " + table
                            + " group by list_source, list_version order by list_source, list_version",
                    sourceRow(list));
            Boolean hit = jdbc.queryForObject(
                    "select exists (select 1 from " + table + " where normalized_name = ?)", Boolean.class, name.value());
            return new ScreeningListMatch(Boolean.TRUE.equals(hit), sources);
        } catch (DataAccessException e) {
            throw new ScreeningSourceUnavailableException("The " + list.displayName() + " could not be read");
        }
    }

    private static RowMapper<ListVersion> sourceRow(ScreeningList list) {
        return (rs, rowNum) -> new ListVersion(list, rs.getString("list_source"), rs.getString("list_version"),
                rs.getObject("loaded_at", OffsetDateTime.class).toInstant());
    }

    private static String tableOf(ScreeningList list) {
        return switch (list) {
            case SANCTIONS -> "sanctions_list_entry";
            case PEP -> "pep_register_entry";
        };
    }
}
