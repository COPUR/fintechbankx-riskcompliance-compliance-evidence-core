-- Compliance computes the sanctions and PEP facts itself (decision 0003):
-- the customer's normalised name is matched exactly against two
-- compliance-owned lists. The lists are loaded by compliance operations
-- with scripts/ops/load-screening-list.sh (one transactional replace per
-- list_source: for example UN, OFAC-SDN, a local list); the service only
-- reads them. The screening evidence records which loads it was matched
-- against (list_versions) and that its facts were COMPUTED.
--
-- normalized_name is the matching key: NFD with combining marks removed,
-- upper case, letters and digits only, single spaces (NormalizedName in the
-- domain and scripts/ops/normalize-screening-name.py, kept in step by shared
-- fixtures). display_name is the name as published, for review only.
-- String columns are VARCHAR, which is what the service's Hibernate
-- validation expects should these tables ever be mapped.

CREATE TABLE sanctions_list_entry (
    entry_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    normalized_name VARCHAR(512) NOT NULL,
    display_name    VARCHAR(512) NOT NULL,
    list_source     VARCHAR(64)  NOT NULL,
    list_version    VARCHAR(128) NOT NULL,
    loaded_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_sanctions_list_entry_name CHECK (normalized_name <> '' AND normalized_name = btrim(normalized_name)),
    CONSTRAINT ck_sanctions_list_entry_source CHECK (list_source ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$'),
    CONSTRAINT ck_sanctions_list_entry_version CHECK (list_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$')
);

CREATE INDEX ix_sanctions_list_entry_name ON sanctions_list_entry (normalized_name);
CREATE INDEX ix_sanctions_list_entry_source ON sanctions_list_entry (list_source, list_version);

COMMENT ON TABLE sanctions_list_entry IS 'Sanctions screening list (UN, OFAC-SDN, local lists), loaded by compliance operations; read-only for the service.';

CREATE TABLE pep_register_entry (
    entry_id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    normalized_name VARCHAR(512) NOT NULL,
    display_name    VARCHAR(512) NOT NULL,
    list_source     VARCHAR(64)  NOT NULL,
    list_version    VARCHAR(128) NOT NULL,
    loaded_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT ck_pep_register_entry_name CHECK (normalized_name <> '' AND normalized_name = btrim(normalized_name)),
    CONSTRAINT ck_pep_register_entry_source CHECK (list_source ~ '^[A-Za-z0-9][A-Za-z0-9._-]*$'),
    CONSTRAINT ck_pep_register_entry_version CHECK (list_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]*$')
);

CREATE INDEX ix_pep_register_entry_name ON pep_register_entry (normalized_name);
CREATE INDEX ix_pep_register_entry_source ON pep_register_entry (list_source, list_version);

COMMENT ON TABLE pep_register_entry IS 'Politically exposed persons register, loaded by compliance operations; read-only for the service.';

-- The evidence row names the list loads a computed screening was matched
-- against: a JSON array of {list, source, version, loadedAt}. Caller-attested
-- rows (v1) consulted no list and keep the empty array; a COMPUTED row must
-- name at least one load.
ALTER TABLE compliance_screening
    ADD COLUMN list_versions JSONB NOT NULL DEFAULT '[]'::jsonb;

ALTER TABLE compliance_screening
    ADD CONSTRAINT ck_compliance_screening_list_versions CHECK (jsonb_typeof(list_versions) = 'array');

ALTER TABLE compliance_screening
    ADD CONSTRAINT ck_compliance_screening_computed_lists
    CHECK (attestation_source <> 'COMPUTED' OR jsonb_array_length(list_versions) > 0);

ALTER TABLE compliance_screening DROP CONSTRAINT ck_compliance_screening_attestation;
ALTER TABLE compliance_screening ADD CONSTRAINT ck_compliance_screening_attestation
    CHECK (attestation_source IN ('CALLER_ATTESTED', 'STAFF_ATTESTED', 'COMPUTED'));

COMMENT ON COLUMN compliance_screening.list_versions IS 'List loads (list, source, version, loadedAt) the facts were matched against; [] for caller-attested facts.';

-- The service reads the lists and nothing more. Loading is done by the schema
-- owner or a loader role the DBA grants SELECT, INSERT, DELETE on both tables
-- (RUNBOOK, "Screening lists"). As in V7, a local single-user run has nothing
-- to separate.
DO $$
DECLARE
    runtime_role text := '${runtime_role}';
BEGIN
    IF runtime_role = current_user THEN
        RAISE NOTICE 'runtime role % is the migration owner (single-user run): privileges not separated', runtime_role;
        RETURN;
    END IF;

    EXECUTE format('REVOKE ALL ON TABLE sanctions_list_entry, pep_register_entry FROM %I, PUBLIC', runtime_role);
    EXECUTE format('GRANT SELECT ON TABLE sanctions_list_entry, pep_register_entry TO %I', runtime_role);
END
$$;
