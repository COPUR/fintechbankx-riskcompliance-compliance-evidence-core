-- Flyway runs as the migration owner (DB_MIGRATION_USERNAME), which owns
-- sc_cmp_evidence and every table in it. The service connects as the runtime
-- role (${runtime_role}, from DB_USERNAME) and gets only what it needs:
--   compliance_screening      SELECT, INSERT (evidence: no UPDATE, DELETE, TRUNCATE)
--   outbox_event              SELECT, INSERT, UPDATE, DELETE (the relay marks, parks and purges rows)
--   legacy_compliance_report  SELECT (written by the backfill role only)
-- Not being the owner, it can neither ALTER nor DROP the tables or the
-- insert-only trigger (V4), and it cannot create objects in the schema.
-- Every later migration that adds a table grants the runtime role explicitly.
--
-- Local single-user runs (no DB_MIGRATION_USERNAME) migrate as the runtime
-- role itself; then there is nothing to separate and this migration only
-- says so, because revoking the owner's own privileges would break later
-- migrations.

DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the migration owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON SCHEMA %I FROM %I', current_schema(), runtime_role);
    EXECUTE format('GRANT USAGE ON SCHEMA %I TO %I', current_schema(), runtime_role);

    EXECUTE format('REVOKE ALL ON TABLE compliance_screening, outbox_event, legacy_compliance_report FROM %I, PUBLIC',
                   runtime_role);
    EXECUTE format('GRANT SELECT, INSERT ON TABLE compliance_screening TO %I', runtime_role);
    EXECUTE format('GRANT SELECT, INSERT, UPDATE, DELETE ON TABLE outbox_event TO %I', runtime_role);
    EXECUTE format('GRANT SELECT ON TABLE legacy_compliance_report TO %I', runtime_role);
END
$$;
