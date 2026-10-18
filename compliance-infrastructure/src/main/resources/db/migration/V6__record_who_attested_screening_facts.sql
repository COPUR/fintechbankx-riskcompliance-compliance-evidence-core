-- Who stated the screening facts: the token azp (client id) of a calling
-- service (attestation_source CALLER_ATTESTED) or the token sub of a
-- compliance officer or administrator screening by hand (STAFF_ATTESTED).
-- A replay must come from the same caller; another caller's request for the
-- same transaction is refused (409) and the evidence is kept.
--
-- Mandatory from the start: this schema has not been released to any
-- environment, so there are no earlier rows to tolerate.

ALTER TABLE compliance_screening ADD COLUMN attested_by VARCHAR(128) NOT NULL;

ALTER TABLE compliance_screening ADD CONSTRAINT ck_compliance_screening_attested_by
    CHECK (length(btrim(attested_by)) > 0);

ALTER TABLE compliance_screening DROP CONSTRAINT ck_compliance_screening_attestation;
ALTER TABLE compliance_screening ADD CONSTRAINT ck_compliance_screening_attestation
    CHECK (attestation_source IN ('CALLER_ATTESTED', 'STAFF_ATTESTED'));

COMMENT ON COLUMN compliance_screening.attested_by IS
    'Token azp of the calling service or sub of the staff member who stated the facts.';
