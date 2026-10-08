# RUNBOOK-EXTRACT-cmp-evidence

Extraction of compliance screening and regulatory report evidence from
`enterprise-loan-management-system` into `svc-cmp-evidence` (this
repository), following the strangler-fig steps of `fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `cmp` / `svc-cmp-evidence` |
| Slice | Transaction compliance screening (sanctions, KYC, PEP; PASS / REVIEW / FAIL, reasons) and the history of regulatory compliance reports |
| Owned data | `db_cmp_evidence_<env>`, schema `sc_cmp_evidence`: `compliance_screening`, `legacy_compliance_report`, `outbox_event` |
| Events | `evt.cmp.compliance.screened.v1` (`Compliance.ComplianceScreening.Screened.v1`, AsyncAPI `api/asyncapi/svc-cmp-evidence.yaml`, Proposed), written through a transactional outbox; callers still use the result synchronously |
| Called by | payment and lending services: `POST /api/v1/compliance/screen`; compliance officers and auditors: `GET /api/v1/compliance/screenings/{transactionId}` |

## 1. Data ownership split

| Monolith object | Owner after the split | Notes |
|---|---|---|
| `compliance_reports` (V13, regulatory reports) | this service, `legacy_compliance_report` | kept as evidence; the monolith table had no foreign keys, so nothing is dropped |
| `compliance-context` JPA adapter (`compliance_results` plus a reasons collection table) | replaced | no monolith migration ever created `compliance_results`; screening results now live in `compliance_screening`, reasons as a JSON array |
| `customers`, `loans` | `svc-cus-profile-kyc`, `svc-ln-loan-lifecycle` | never copied here; `customer_id` is the caller's id, kept as text |
| Open-finance `compliance_reports` MongoDB collection | open-finance services | consent analytics, a different concept; not moved |

Flyway migrations: `compliance-infrastructure/src/main/resources/db/migration/V1__create_compliance_screening.sql`, `V2__create_legacy_compliance_report.sql`, `V3__create_outbox.sql`, `V4__make_compliance_screening_insert_only.sql`, `V5__park_outbox_events.sql`, `V6__record_who_attested_screening_facts.sql`, `V7__grant_runtime_role_least_privilege.sql`. The service never reads monolith tables and the monolith must not read `sc_cmp_evidence`.

Screening results store the facts they were decided on (amount, currency, sanctions/KYC/PEP flags, rule set version), where they came from (`attestation_source`: `CALLER_ATTESTED` for a listed service, `STAFF_ATTESTED` for a compliance officer or administrator) and who stated them (`attested_by`: the token `azp` of the service or `sub` of the staff member; V6). The flags come from the caller ([decision 0001](../architecture/decisions/0001-screening-facts-are-caller-attested.md)). A replay from another caller is a 409.

### Database roles (DBA bootstrap)

| Role | Used by | Privileges |
|---|---|---|
| migration owner (`compliance_evidence_owner`, secret `<env>/compliance-evidence-service/db-migration`) | Flyway only (`DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`, Helm `externalSecret.migrationSecretName`) | owns `sc_cmp_evidence` and its objects; needs `CREATE` on the database |
| `compliance_evidence_app` (runtime, `DB_USERNAME`, secret `<env>/compliance-evidence-service/db-app`) | the service | granted by V7 (Flyway placeholder `runtime_role`): `USAGE` on the schema; `SELECT, INSERT` on `compliance_screening` (no `UPDATE`, `DELETE`, `TRUNCATE`); `SELECT, INSERT, UPDATE, DELETE` on `outbox_event` (the relay marks, parks and purges rows); `SELECT` on `legacy_compliance_report`. Not the owner, so it cannot `ALTER`/`DROP` the tables or disable the V4 trigger (`ComplianceServiceIT.theRuntimeRoleCannotChangeOrRemoveEvidence`) |
| backfill role | `db/backfill/run-backfill.sh` | `CREATE` on the database (staging schema); `SELECT, INSERT, UPDATE, DELETE` on `legacy_compliance_report` only |

`compliance_screening` is insert-only evidence for the runtime role: V7 grants it only `SELECT, INSERT`, and the `tr_compliance_screening_insert_only` trigger (V4) refuses `UPDATE` and `DELETE`. The table owner (the migration role) can still disable the trigger or `TRUNCATE`, so the guarantee holds only once the DBA bootstrap gives the pods a separate runtime role and the owner credential leaves the pods (migration Job follow-up). Nothing yet makes tampering by a DBA detectable. Any future retention purge needs its own reviewed migration.

DBA bootstrap, per environment, before the first deploy: create both roles (the owner with `CREATE` on `db_cmp_evidence_<env>`, the runtime role with `LOGIN` only, no membership in the owner), write their `{"username","password"}` to the two secrets Terraform creates, and set Helm `externalSecret.migrationSecretName`. Flyway then migrates as the owner and V7 grants the runtime role. Without `DB_MIGRATION_*` (local runs) Flyway uses the app credential and V7 changes nothing.

Still open: Flyway runs at pod startup, so the pods hold the owner credential as well; anyone with the pod's environment can act as the owner. Moving Flyway to a migration Job (or pipeline step) that alone mounts the `db-migration` secret, with `spring.flyway.enabled=false` in the pods, closes that. A DBA (or the RDS master user) can always change rows; tamper evidence against them (for example a hash chain) is not built.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<compliance service conninfo>"`

1. Exports `compliance_reports` in one read-only snapshot.
2. Stages them in `backfill_stage` and upserts them into `legacy_compliance_report` (`02_transform_into_compliance_service.sql`). Every column is kept. Reports still move through review and submission in the monolith until cut-over, so a re-run refreshes rows already copied, and a report deleted in the monolith is deleted from the copy (legacy copy only; screening evidence is never touched). An empty export against a non-empty copy is refused.
3. Compares report counts and the loan, amount and findings totals with the monolith, and lists any staged report whose copy differs and any copied report the monolith no longer has (`03_reconcile.sql`). Any difference fails the run.

The backfill is independent of the other contexts' backfills and idempotent. `scripts/migration/verify-backfill.sh` rehearses it on a scratch PostgreSQL (two runs, a workflow change in the source and a third run, a deletion in the source and a fourth run, and an empty export that must be refused) and runs in CI (`deploy/data-split-rehearsal`).

**Report files are not migrated.** `report_file_path` is copied as text, but the files it points to (generated report documents in the monolith's storage) are not moved. Before step 3 the squad needs a plan: copy the files to this service's storage (an S3 bucket owned by `svc-cmp-evidence`, KMS-encrypted, retention per regulation) and rewrite the paths, or keep them where they are with read access documented. Until then the paths in `legacy_compliance_report` point into monolith storage.

## 3. Cutover plan

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy the service with the chart default `OUTBOX_RELAY_ENABLED: "false"`; screenings are stored and their events wait in `outbox_event`. Run the backfill; reconcile | drop `sc_cmp_evidence`, nothing else changed |
| 2 | Payments call `POST /api/v1/compliance/screen` with the payment id as `transactionId` and a client-credentials token (`SERVICE` role), behind a flag | flag off; payments keep their local checks |
| 3 | **Only when** report generation and the review/submission workflow run in `svc-cmp-evidence` (not yet built) **and** the report-file plan above is done: monolith stops writing `compliance_reports`; run the backfill a last time. Until then the monolith stays the writer and the backfill keeps re-running as a mirror | monolith table is still intact |
| 4 | After the next regulatory reporting cycle: drop the monolith table | restore from snapshot |
| 5 | Preconditions: the mesh contract lists `msk` for `compliance-evidence-service` (allow-egress-msk generated for namespace `compliance`); topic `evt.cmp.compliance.screened.v1` exists on MSK (no DLQ: this service consumes nothing); `msk_cluster_arn` is set. Then turn the relay on with `--set-string config.OUTBOX_RELAY_ENABLED=true` (or in the environment's values file); watch `outbox_pending_events` drain and `outbox_parked_events` stay 0; consumers subscribe to `evt.cmp.compliance.screened.v1` | set it back to `"false"`; events stay in the outbox and are sent in order once it is back on |

### Parked outbox events

The relay parks a row it can never send instead of stalling every later
event. Only failures caused by the row itself are poison and park it at once:
`RecordTooLargeException`, `SerializationException`, `InvalidTopicException`.
Every other failure is about the producer or the cluster, not the row: a
retriable error or timeout (broker or egress outage, a topic not created yet,
`UnknownTopicOrPartitionException`), an authentication or authorisation error
(`SaslAuthenticationException` from an IRSA/STS hiccup,
`TopicAuthorizationException` during an ACL or IAM-policy rollout), an
unclassified `KafkaException` or any other exception. It stops the batch and is
retried on the next run, however many attempts that takes; it parks the row
only once the row has kept failing for longer than
`compliance.outbox.relay.retryable-park-after`
(`OUTBOX_RELAY_RETRYABLE_PARK_AFTER`, default `PT24H`) since its first failure
(`first_failed_at`, V8), and then at most that one row per run. So an outage or
a credentials problem of minutes or hours delays events but parks none, and
nothing needs replaying once it is over.
`compliance.outbox.relay.max-attempts` (`OUTBOX_RELAY_MAX_ATTEMPTS`, default 10)
never parks a row: from that many failed sends of one row on, each retry is
logged at ERROR instead of WARN. Parked rows keep `parked_at`, `first_failed_at`,
`attempts` and `last_error` and are skipped by the relay.

Alerts: `outbox_parked_events{service="svc-cmp-evidence"} > 0` (rows that need
a decision), and `outbox_oldest_pending_age_seconds` above a few minutes (the
relay is stalled on a retriable failure, or off; the backlog is waiting, not
lost). Two replicas never send the same row: the relay holds a PostgreSQL
advisory lock for its run (`ComplianceServiceIT.twoRelaysRunningConcurrentlySendEachRowExactlyOnce`).

Replay once the cause is fixed (topic created, ACL granted, payload issue
resolved). The relay picks the rows up on its next run, in `created_seq` order:

```sql
-- inspect
select event_id, topic, aggregate_id, attempts, last_error, first_failed_at, parked_at
from sc_cmp_evidence.outbox_event
where parked_at is not null and published_at is null
order by created_seq;

-- un-park (one event, or drop the event_id condition for all of them)
update sc_cmp_evidence.outbox_event
set parked_at = null, first_failed_at = null, attempts = 0, last_error = null
where parked_at is not null and published_at is null
  and event_id = '<event id>';
```

Run it as the migration owner or the runtime role (both may update
`outbox_event`). An event whose payload can never be sent (for example too
large) needs a fix in the service and a new event, not a replay; leave it
parked and record the decision here.

## 4. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations; Hibernate validates the entity at startup
- [x] One result per transaction: replays (same customer, same facts, same caller) return the stored result; a reused id for another customer, with any different fact or from another caller is a 409; screening rows are insert-only (V4 trigger; V7 grants once the DBA bootstrap separates the roles)
- [x] Screened facts, rule set version and attestation source stored with each result
- [x] Screening results readable by compliance officers and auditors; customers cannot screen or read
- [x] Compliance report backfill rehearsed with reconciliation in CI
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] Payment services call this API (follow-up in the payments repositories)
- [x] Screening events written through a transactional outbox in the screening's transaction; one event per new screening, none on retries or when a concurrent duplicate loses (`ComplianceServiceIT`); screening inputs not published
- [x] Outbox rows that can never be sent are parked (non-retryable error at once; retriable errors only after `retryable-park-after`, default 24 h), skipped, counted by `outbox_parked_events` and replayed by hand; backlog age in `outbox_oldest_pending_age_seconds`; one sender at a time (`OutboxRelayTest`, `ComplianceServiceIT`)
- [ ] AsyncAPI catalog entry (proposed in asyncapi-catalog PR #11, not merged) matches `api/asyncapi/svc-cmp-evidence.yaml` (provider copy changes `screeningId` from `format: uuid` to the `CMP-<uuid>` pattern)
- [ ] Topic `evt.cmp.compliance.screened.v1` created on the platform cluster (producer only, so no DLQ); IRSA `msk_cluster_arn` set
- [ ] Mesh contract lists `msk` for `compliance-evidence-service` (allow-egress-msk generated for namespace `compliance`); until then the chart keeps the relay off
- [ ] Report generation and the review/submission workflow moved here (precondition for step 3; today only the history is mirrored)
- [ ] Plan for report files at `report_file_path` (not migrated)
- [x] Runtime role separated from the migration owner: V7 grants, Flyway `DB_MIGRATION_*` credentials, Helm migration secret, Terraform secret; the runtime role cannot `UPDATE`/`DELETE`/`TRUNCATE` evidence or disable the trigger (`ComplianceServiceIT`)
- [ ] DBA bootstrap of `compliance_evidence_owner` and `compliance_evidence_app` per environment, secrets filled
- [ ] Flyway moved out of the pods into a migration Job, so the pods no longer hold the owner credential
- [ ] Production backfill and reconciliation report attached here
