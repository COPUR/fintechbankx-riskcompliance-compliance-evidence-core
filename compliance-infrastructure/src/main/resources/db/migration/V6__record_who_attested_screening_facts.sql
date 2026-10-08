-- Who stated the screening facts: the token azp (client id) of a calling
-- service (attestation_source CALLER_ATTESTED) or the token sub of a
-- compliance officer or administrator screening by hand (STAFF_ATTESTED).
-- A replay must come from the same caller; another caller's request for the
-- same transaction is refused (409) and the evidence is kept.
--
-- Rows written before this migration cannot be backfilled (insert-only
-- evidence, and the caller was never recorded), so they keep NULL, which
-- means "not recorded". The NOT VALID check makes attested_by mandatory for
-- every row inserted from now on without rewriting the old ones.

ALTER TABLE compliance_screening ADD COLUMN attested_by VARCHAR(128);

ALTER TABLE compliance_screening ADD CONSTRAINT ck_compliance_screening_attested_by
    CHECK (attested_by IS NOT NULL AND length(btrim(attested_by)) > 0) NOT VALID;

ALTER TABLE compliance_screening DROP CONSTRAINT ck_compliance_screening_attestation;
ALTER TABLE compliance_screening ADD CONSTRAINT ck_compliance_screening_attestation
    CHECK (attestation_source IN ('CALLER_ATTESTED', 'STAFF_ATTESTED'));

COMMENT ON COLUMN compliance_screening.attested_by IS
    'Token azp of the calling service or sub of the staff member who stated the facts; NULL only for rows written before V6 (not recorded).';
