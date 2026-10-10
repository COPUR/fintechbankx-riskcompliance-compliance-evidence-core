-- Role bootstrap for svc-cmp-evidence (schema sc_cmp_evidence), run by the DBA
-- once per environment before the first deploy, connected to the service's own
-- database (db_cmp_evidence_<env>) as the Aurora master user:
--
--   psql "<admin connection to db_cmp_evidence_<env>>" -v ON_ERROR_STOP=1 -f db/bootstrap/roles.sql
--   psql ... -c '\password compliance_evidence_owner'
--   psql ... -c '\password compliance_evidence_app'
--
-- It creates two distinct LOGIN roles and the schema, and nothing else:
--   compliance_evidence_owner  schema owner; Flyway runs as it, in the migration
--                              Job only (secret <env>/compliance-evidence-service/db-migration)
--   compliance_evidence_app    runtime role (DB_USERNAME; secret
--                              <env>/compliance-evidence-service/db-app). It gets no
--                              privilege here: the migrations grant it what it needs
--                              (V7: SELECT, INSERT on the evidence table compliance_screening,
--                              no UPDATE, DELETE or TRUNCATE; V12: SELECT on flyway_schema_history).
--
-- No password is in this file. Set both out of band (psql \password, above),
-- then write {"username","password"} to the two Secrets Manager secrets
-- Terraform creates. A role without a password cannot log in with password
-- authentication, so nothing connects before that step.
--
-- Idempotent: a re-run creates nothing new and changes no grant. It fails, and
-- changes nothing, if either role exists with SUPERUSER, CREATEROLE, CREATEDB,
-- REPLICATION or BYPASSRLS, without LOGIN, or if the runtime role is a member
-- of the owner. The runbook (section 1, "Database roles") and decision 0002
-- reference this file; terraform-modules ships only a generic role_bootstrap_sql.
--
-- Plain SQL (no psql meta-commands), so RoleBootstrapIT runs the same file
-- through JDBC: twice, then every migration as the owner.

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'compliance_evidence_owner') THEN
        CREATE ROLE compliance_evidence_owner LOGIN;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'compliance_evidence_app') THEN
        CREATE ROLE compliance_evidence_app LOGIN;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles
               WHERE rolname IN ('compliance_evidence_owner', 'compliance_evidence_app')
                 AND (NOT rolcanlogin OR rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls)) THEN
        RAISE EXCEPTION 'compliance_evidence_owner and compliance_evidence_app must be LOGIN roles without SUPERUSER, CREATEROLE, CREATEDB, REPLICATION or BYPASSRLS';
    END IF;
    IF pg_has_role('compliance_evidence_app', 'compliance_evidence_owner', 'MEMBER') THEN
        RAISE EXCEPTION 'compliance_evidence_app must not be a member of compliance_evidence_owner: the runtime role would act as the schema owner';
    END IF;

    -- CREATE SCHEMA ... AUTHORIZATION needs the caller to be able to act as the
    -- owner. A superuser can; the Aurora master user (rds_superuser, not a
    -- superuser) gets membership once, on the first run.
    IF NOT pg_has_role(current_user, 'compliance_evidence_owner', 'MEMBER') THEN
        EXECUTE format('GRANT compliance_evidence_owner TO %I', current_user);
    END IF;

    EXECUTE format('GRANT CONNECT ON DATABASE %I TO compliance_evidence_owner, compliance_evidence_app', current_database());
END
$$;

CREATE SCHEMA IF NOT EXISTS sc_cmp_evidence AUTHORIZATION compliance_evidence_owner;
ALTER SCHEMA sc_cmp_evidence OWNER TO compliance_evidence_owner;
-- Nobody but the owner has anything in the schema until V7 grants the runtime role USAGE.
REVOKE ALL ON SCHEMA sc_cmp_evidence FROM PUBLIC;
