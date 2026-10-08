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

Flyway migrations: `compliance-infrastructure/src/main/resources/db/migration/V1__create_compliance_screening.sql`, `V2__create_legacy_compliance_report.sql`, `V3__create_outbox.sql`. The service never reads monolith tables and the monolith must not read `sc_cmp_evidence`.

## 2. Backfill and reconciliation

`db/backfill/run-backfill.sh "<monolith conninfo>" "<compliance service conninfo>"`

1. Exports `compliance_reports` in one read-only snapshot.
2. Stages them in `backfill_stage` and upserts them into `legacy_compliance_report` (`02_transform_into_compliance_service.sql`). Every column is kept. Reports still move through review and submission in the monolith until cut-over, so a re-run refreshes rows already copied.
3. Compares report counts and the loan, amount and findings totals with the monolith, and lists any staged report whose copy differs (`03_reconcile.sql`). Any difference fails the run.

The backfill is independent of the other contexts' backfills and idempotent. `scripts/migration/verify-backfill.sh` rehearses it on a scratch PostgreSQL (two runs, then a workflow change in the source and a third run) and runs in CI (`deploy/data-split-rehearsal`).

## 3. Cutover plan

| Step | Action | Rollback |
|---|---|---|
| 1 | Deploy the service with `OUTBOX_RELAY_ENABLED=false` until topic `evt.cmp.compliance.screened.v1` exists on MSK; run the backfill; reconcile | drop `sc_cmp_evidence`, nothing else changed |
| 2 | Payments call `POST /api/v1/compliance/screen` with the payment id as `transactionId` and a client-credentials token (`SERVICE` role), behind a flag | flag off; payments keep their local checks |
| 3 | Monolith stops writing `compliance_reports`; run the backfill a last time | monolith table is still intact |
| 4 | After the next regulatory reporting cycle: drop the monolith table | restore from snapshot |
| 5 | Enable the outbox relay (`OUTBOX_RELAY_ENABLED=true`); consumers subscribe to `evt.cmp.compliance.screened.v1` | relay off; events stay in the outbox and are sent in order once it is back on |

## 4. Acceptance checklist

- [x] Service builds and tests standalone (`ci/build`, `ci/test`, including PostgreSQL integration tests)
- [x] Own schema and migrations; Hibernate validates the entity at startup
- [x] One result per transaction: retries return the stored result, a reused id for another customer is a 409
- [x] Screening results readable by compliance officers and auditors; customers cannot screen or read
- [x] Compliance report backfill rehearsed with reconciliation in CI
- [x] Container image, Helm chart, Terraform validate in CI (`Deployability` workflow)
- [ ] Payment services call this API (follow-up in the payments repositories)
- [x] Screening events written through a transactional outbox in the screening's transaction; one event per new screening, none on retries or when a concurrent duplicate loses (`ComplianceServiceIT`); screening inputs not published
- [ ] AsyncAPI catalog mirror updated from `api/asyncapi/svc-cmp-evidence.yaml` (provider copy changes `screeningId` from `format: uuid` to the `CMP-<uuid>` pattern)
- [ ] Topic `evt.cmp.compliance.screened.v1` and its DLQ created on the platform cluster; IRSA `msk_cluster_arn` set
- [ ] Report generation moved here (today only the history is migrated)
- [ ] Production backfill and reconciliation report attached here
