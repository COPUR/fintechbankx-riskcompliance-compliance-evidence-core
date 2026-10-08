-- Screening results are evidence: written once, never changed or removed by
-- the application. The runtime role gets only SELECT and INSERT on
-- compliance_screening (DBA bootstrap, see the runbook); this trigger also
-- refuses UPDATE and DELETE from any role, including the migration owner,
-- so a change needs a deliberate, reviewed migration that drops it.
--
-- TRUNCATE is not covered by row triggers; no runtime or backfill role is
-- granted TRUNCATE. Retention purges, once regulatory periods are set, need
-- their own reviewed migration.

CREATE FUNCTION compliance_screening_insert_only() RETURNS trigger
    LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'compliance_screening is insert-only evidence; % refused', TG_OP
        USING ERRCODE = 'restrict_violation',
              HINT = 'Screening results are never changed or deleted by the service.';
END;
$$;

CREATE TRIGGER tr_compliance_screening_insert_only
    BEFORE UPDATE OR DELETE ON compliance_screening
    FOR EACH ROW EXECUTE FUNCTION compliance_screening_insert_only();
