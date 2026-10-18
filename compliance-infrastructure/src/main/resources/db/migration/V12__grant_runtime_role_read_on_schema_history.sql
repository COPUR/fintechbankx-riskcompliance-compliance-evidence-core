-- The service pods no longer migrate: a Helm pre-install/pre-upgrade Job runs
-- Flyway as the migration owner, and the pods connect only as the runtime role
-- (${runtime_role}). At startup the service validates the schema history as
-- that role and refuses to start while a migration is pending, so the role may
-- read flyway_schema_history (Flyway's default table name; application.yml does
-- not change it). It gets SELECT only: it cannot record, repair or remove a
-- migration.
--
-- Local single-user runs (no DB_MIGRATION_USERNAME) migrate as the runtime
-- role itself, which already owns the table; as in V7 nothing is granted.

DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the migration owner (single-user run): nothing to grant', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON TABLE %I.flyway_schema_history FROM %I, PUBLIC', current_schema(), runtime_role);
    EXECUTE format('GRANT SELECT ON TABLE %I.flyway_schema_history TO %I', current_schema(), runtime_role);
END
$$;
