package com.bank.compliance;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The runtime role at startup, after Flyway has validated the schema
 * (decision 0002: the service pods hold only the runtime role, and V7 and V12
 * leave the evidence insert-only for it). The DBA bootstrap
 * (db/bootstrap/roles.sql) and the grants hold the guarantee; this guard
 * asserts it on the connection the service will use, as {@code current_user}:
 * <ul>
 * <li>not a member of the owner of {@code compliance_screening}
 * ({@code pg_has_role(..., 'MEMBER')}: a member acts as the owner and could
 * disable the trigger, TRUNCATE or drop the table);</li>
 * <li>owns no table in the schema;</li>
 * <li>has no CREATE on the schema;</li>
 * <li>has no UPDATE, DELETE, TRUNCATE or TRIGGER on {@code compliance_screening}
 * ({@code has_table_privilege}).</li>
 * </ul>
 * Any finding fails the context, so a pod on a wrongly bootstrapped or
 * over-granted role never serves. The message names the property and the
 * checks, never the role name, the URL or a credential. Not loaded by the
 * migrate-only Job, which connects as the owner on purpose.
 */
public final class RuntimeRoleGuard {

    static final String RUNTIME_USERNAME = "DB_USERNAME";
    static final String EVIDENCE_TABLE = "compliance_screening";

    private static final String SQL = """
            select pg_has_role(current_user, c.relowner, 'MEMBER') as member_of_owner,
                   (select count(*) from pg_class t
                     where t.relnamespace = n.oid and t.relkind in ('r', 'p')
                       and pg_get_userbyid(t.relowner) = current_user) as owned_tables,
                   has_schema_privilege(current_user, n.oid, 'CREATE') as create_on_schema,
                   has_table_privilege(current_user, c.oid, 'UPDATE') as update_on_evidence,
                   has_table_privilege(current_user, c.oid, 'DELETE') as delete_on_evidence,
                   has_table_privilege(current_user, c.oid, 'TRUNCATE') as truncate_on_evidence,
                   has_table_privilege(current_user, c.oid, 'TRIGGER') as trigger_on_evidence
              from pg_class c
              join pg_namespace n on n.oid = c.relnamespace
             where n.nspname = ? and c.relname = ? and c.relkind in ('r', 'p')
            """;

    private final JdbcTemplate jdbc;
    private final String schema;

    RuntimeRoleGuard(DataSource dataSource, String schema) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.schema = schema;
    }

    void verify() {
        List<Map<String, Object>> rows = jdbc.queryForList(SQL, schema, EVIDENCE_TABLE);
        if (rows.isEmpty()) {
            throw new IllegalStateException("table " + schema + "." + EVIDENCE_TABLE
                    + " not found: the migration Job has not run against this database (decision 0002)");
        }
        Map<String, Object> row = rows.get(0);
        List<String> findings = new ArrayList<>();
        if (flag(row, "member_of_owner")) {
            findings.add("it is a member of the owner of " + EVIDENCE_TABLE + " (or is the owner)");
        }
        if (((Number) row.get("owned_tables")).longValue() > 0) {
            findings.add("it owns a table in the schema");
        }
        if (flag(row, "create_on_schema")) {
            findings.add("it has CREATE on the schema");
        }
        for (String privilege : List.of("UPDATE", "DELETE", "TRUNCATE", "TRIGGER")) {
            if (flag(row, privilege.toLowerCase() + "_on_evidence")) {
                findings.add("it has " + privilege + " on " + EVIDENCE_TABLE);
            }
        }
        if (!findings.isEmpty()) {
            throw new IllegalStateException(RUNTIME_USERNAME + " (the runtime role the service connects as) must be least"
                    + " privilege on schema " + schema + " (decision 0002; db/bootstrap/roles.sql, V7 and V12): "
                    + String.join("; ", findings) + ". Fix the role bootstrap or the grants, never the service");
        }
    }

    private static boolean flag(Map<String, Object> row, String column) {
        return Boolean.TRUE.equals(row.get(column));
    }
}
