# RUNBOOK-EXTRACT-cmp-evidence

Extraction of compliance screening and regulatory report evidence from
`enterprise-loan-management-system` into `svc-cmp-evidence` (this
repository), following the strangler-fig steps of `fbx-monolith-extraction`.

| Field | Value |
|---|---|
| Context / service | `cmp` / `svc-cmp-evidence` |
| Slice | Transaction compliance screening (sanctions, KYC, PEP; PASS / REVIEW / FAIL, reasons) and the history of regulatory compliance reports |
| Owned data | `db_cmp_evidence_<env>`, schema `sc_cmp_evidence`: `compliance_screening`, `legacy_compliance_report`, `outbox_event` |
| Events | aggregate topic `evt.cmp.compliance.v1` (ADR-019: one topic per aggregate, keyed by the screening id), carrying `Compliance.ComplianceScreening.Screened.v1` with the record headers `eventType`, `eventId` and `correlationId` (AsyncAPI `api/asyncapi/svc-cmp-evidence.yaml`, Proposed), written through a transactional outbox; consumers route on the `eventType` header and skip types they do not handle; callers still use the result synchronously |
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

`db/backfill/run-backfill.sh "<monolith conninfo>" "<compliance service conninfo>" <report currency>`

`<report currency>` is the ISO 4217 code of the monolith's report amounts: `compliance_reports.total_amount` has no currency, and the home-currency decision is open, so the operator states it (the monolith code assumes USD) and the run refuses to start without a valid code. It is stored in `legacy_compliance_report.total_amount_currency` (V9).

1. Exports `compliance_reports` and reads its totals in one read-only `REPEATABLE READ` snapshot, so a report written meanwhile cannot make the copy and the totals disagree.
2. Stages them in `backfill_stage` and upserts them into `legacy_compliance_report` (`02_transform_into_compliance_service.sql`). Every column is kept. Reports still move through review and submission in the monolith until cut-over, so a re-run refreshes rows already copied, and a report deleted in the monolith is deleted from the copy (legacy copy only; screening evidence is never touched). An empty export against a non-empty copy is refused.
3. Compares report counts and the loan, amount (per currency) and findings totals with the monolith's snapshot totals, and lists any staged report whose copy differs in any of the 23 copied columns and any copied report the monolith no longer has (`03_reconcile.sql`). Any difference fails the run.

The backfill is independent of the other contexts' backfills and idempotent. `scripts/migration/verify-backfill.sh` rehearses it on a scratch PostgreSQL (a run without a report currency that must be refused, two runs, a workflow change in the source and a third run, a deletion in the source and a fourth run, an empty export that must be refused, and drift planted in each column the reconciliation once skipped) and runs in CI (`deploy/data-split-rehearsal`).

**Report files are not migrated.** `report_file_path` is copied as text, but the files it points to (generated report documents in the monolith's storage) are not moved. Before step 3 the squad needs a plan: copy the files to this service's storage (an S3 bucket owned by `svc-cmp-evidence`, KMS-encrypted, retention per regulation) and rewrite the paths, or keep them where they are with read access documented. Until then the paths in `legacy_compliance_report` point into monolith storage.

## 3. Cutover plan

### Deployment prerequisites

Before step 1, in each environment:

- DBA bootstrap of both roles done and their secrets filled (section 1, "Database roles").
- ConfigMap `rds-ca-bundle` (key `global-bundle.pem`, the Amazon RDS CA bundle) exists in namespace `compliance`. The platform's trust-manager Bundle in the service-mesh repository is meant to publish it (cicd-templates 4f0f266); it is on service-mesh branch `claude/platform-deployable-tkl0z7` (5e756f0, `k8s/platform/cert-manager/bundle-rds-ca.yaml`) and applies once that merges, so check the namespace. The chart mounts it read-only at `/etc/fintechbankx/rds-ca` and the volume is not optional: without the ConfigMap the pods do not start. Install order: cert-manager, trust-manager and the `rds-ca-bundle` Bundle must be installed before this chart; a pod scheduled earlier stays `ContainerCreating` until the ConfigMap appears.
- `config.DB_URL` is the Terraform output `jdbc_url`, which uses `sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem`: the driver verifies Aurora's certificate and host name (`sslmode=require` would encrypt but trust any certificate). The chart refuses to render a `jdbc:postgresql` URL without `sslmode=verify-full`. Flyway, as the migration owner, connects through the same URL, so both roles are verified.
- Aurora TLS guards: `config.DB_URL` carries `sslmode=verify-full` and `sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem` exactly once each and no `sslfactory`, `sslhostnameverifier` or `sslpasswordcallback`, and no other config key holds a database URL (`SPRING_DATASOURCE_*URL`, `SPRING_FLYWAY_URL`, `SPRING_APPLICATION_JSON`); the chart refuses to render otherwise, and with `DB_SSL_ROOT_CERT` set (always, in the chart) the service's `DatabaseTlsGuard` refuses to start on any datasource or Flyway URL that PgJDBC would not verify against that bundle.
- Secrets Manager secrets (`db-app`, `db-migration`) use the secrets-only KMS key `alias/<name>-secrets`, tagged `fintechbankx.io/secrets=true`. Aurora storage, snapshots and Performance Insights use the separate, untagged `alias/<name>-db` key, which the External Secrets role cannot decrypt (ADR-023). On an environment applied before this split, Terraform points the two secrets at the new key and Secrets Manager re-encrypts their current versions; an External Secrets sync error during the apply is possible and clears on the next refresh.
- An existing Helm release from before `app.kubernetes.io/component: service` was added to the selectors cannot be upgraded, because a Deployment's selector is immutable. Delete the Deployment (`kubectl -n compliance delete deployment <release>`), then run `helm upgrade`. Nothing is installed today.

| Step | Action | Owner | Rollback trigger | Rollback |
|---|---|---|---|---|
| 1 | Deploy the service with the chart default `OUTBOX_RELAY_ENABLED: "false"`; screenings are stored and their events wait in `outbox_event`. Run the backfill; reconcile | compliance squad (deploy), DBA (bootstrap, backfill) | reconciliation fails, or the service is not ready within 10 min of the deploy | drop `sc_cmp_evidence`, nothing else changed  |
| 2 | Precondition: `sanctionsHit`, `pep` and `kycVerified` have a real source, not caller constants (parity and test runs may use constants). Payments call `POST /api/v1/compliance/screen` with the payment id as `transactionId` and a client-credentials token (`SERVICE` role), behind a flag | payments squad, with the compliance squad | screening 5xx rate above 1 % over 5 min, p99 above 2 s, or any 400 caused by a missing field | flag off; payments keep their local checks  |
| 3 | **Only when** report generation and the review/submission workflow run in `svc-cmp-evidence` (not yet built) **and** the report-file plan above is done: monolith stops writing `compliance_reports`; run the backfill a last time. Until then the monolith stays the writer and the backfill keeps re-running as a mirror | compliance squad, with the monolith owner | last backfill does not reconcile, or a report written in the monolith after the stop | monolith table is still intact  |
| 4 | After the next regulatory reporting cycle: drop the monolith table | monolith owner, compliance squad sign-off | any open regulator query on the period, or a reconciliation gap found afterwards | restore from snapshot  |
| 5 | Preconditions: the mesh contract lists `msk` for `compliance-evidence-service` (allow-egress-msk generated for namespace `compliance`); topic `evt.cmp.compliance.v1` exists on MSK (no DLQ: this service consumes nothing; the per-event topic `evt.cmp.compliance.screened.v1` is retired and must not be created, ADR-019 section 8); `msk_cluster_arn` is set. Then turn the relay on with `--set-string config.OUTBOX_RELAY_ENABLED=true` (or in the environment's values file); watch `outbox_pending_events` drain and `outbox_parked_rows` stay 0; consumers subscribe to `evt.cmp.compliance.v1`, filter on the `eventType` header and skip any other type without failing or dead-lettering it | compliance squad, platform (mesh, MSK) | `outbox_parked_events_total` increases (OutboxEventsParked), or `outbox_oldest_pending_age_seconds` above the alert threshold (900 s for 5 min, see "Parked outbox events") | set it back to `"false"`; events stay in the outbox and are sent in order once it is back on  |

Owners and triggers are Proposed; the owning squads confirm them before step 1.

### Parked outbox events

Failure handling follows ADR-021 decision 4 (fintechbankx-governance
adr-runbooks #10, e6dd76a). Payload errors (`RecordTooLargeException`,
`SerializationException`, `InvalidTopicException`) park the row
(`parked_at`, `attempts`, `last_error`) and the relay continues with the next
row. Everything else stops the batch without marking the row or anything after
it, is retried with backoff and alerts; the relay never parks such rows. That
covers retriable errors and timeouts (broker or egress outage, a topic not
created yet, `UnknownTopicOrPartitionException`), authentication and
authorisation errors (`SaslAuthenticationException` from an IRSA/STS hiccup,
`TopicAuthorizationException` during an ACL or IAM-policy rollout), an
unclassified `KafkaException` and any other exception. The row keeps
`attempts` 0 and `last_error` empty; the error is in the log and in
`outbox_send_failures_total{exception=...}`. `first_failed_at` (V8) is no
longer written. `compliance.outbox.relay.max-attempts`
(`OUTBOX_RELAY_MAX_ATTEMPTS`, default 10) never parks a row: from that many
consecutive stopped runs on, each failure is logged at ERROR instead of WARN.

After a run that stopped on a failure the relay backs off: it waits
`compliance.outbox.relay.backoff-initial` (`PT1S`), doubling per stopped run up
to `backoff-max` (`PT5M`), and resets after a run that does not stop. The
backoff is held in memory by the relay, not on the row.

The backoff and the ERROR escalation are per replica (accepted, Proposed):
the advisory lock lets one replica send at a time, but each replica keeps
its own backoff, so with N replicas a stalled cluster is retried up to N
times as often as configured and `max-attempts` counts stopped runs per pod.
With the chart's 3 replicas and `backoff-max` 5 min that is about one try
every 100 s. A cluster-wide backoff (next-attempt time in a relay state
row) is the alternative if retry load matters.

Alerts: the rules live in platform observability
(fintechbankx-platform-observability-sre-operations) PR #11 at commit
`eca7aa0`, not merged yet. Platform owns them; this service ships no alert
rule and the chart ships no PrometheusRule. All three select on the
`service_id` label, scraped from the pod label `fintechbankx.io/service-id`
(the chart sets `svc-cmp-evidence`; the deployability job asserts it), and
route by squad. Owning squad:
`compliance` (Risk and Compliance Decisioning Squad). Scraping relies on the
pod annotations `prometheus.io/scrape`, `prometheus.io/port` (management port)
and `prometheus.io/path` (`/actuator/prometheus`); the deployability job
asserts all three.

```promql
# One threshold, used by this rule and by the cut-over rollback trigger: 900 s,
# three times backoff-max (5 min), so a single maximum backoff never fires it.
# OutboxRelayStalled: the relay is stalled (outage, credentials, ACL) or off; the backlog waits, it is not lost.
# for: 5m, severity: critical, squad: compliance
max(outbox_oldest_pending_age_seconds{service_id="svc-cmp-evidence"}) > 900

# OutboxSendFailures: any failed send in the last 10 minutes (by exception class in the dashboard).
# severity: warning, squad: compliance
increase(outbox_send_failures_total{service_id="svc-cmp-evidence"}[10m]) > 0

# OutboxEventsParked: any increase of the counter over 15 minutes, no for clause,
# severity warning, squad: compliance. Operator parks (OperatorPark) also fire it.
increase(outbox_parked_events_total{service_id="svc-cmp-evidence"}[15m]) > 0

# Current number of parked rows (dashboard): outbox_parked_rows{service_id="svc-cmp-evidence"}
```

Dependency: PR #11 also widens the AMP remote-write keep regex to the
`outbox_` series, so these rules can fire once it merges. Every meter also
carries the common tags `app` (`compliance-evidence-service`) and `squad`
(`compliance`).

Two replicas never send the same row: the relay holds a PostgreSQL
advisory lock for its run (`ComplianceServiceIT.twoRelaysRunningConcurrentlySendEachRowExactlyOnce`).

Replay once the cause is fixed (topic created, ACL granted, payload issue
resolved). The relay picks the rows up on its next run, in `created_seq` order:

```sql
-- inspect
select event_id, topic, aggregate_id, attempts, last_error, parked_at
from sc_cmp_evidence.outbox_event
where parked_at is not null and published_at is null
order by created_seq;

-- un-park (one event, or drop the event_id condition for all of them)
update sc_cmp_evidence.outbox_event
set parked_at = null, park_counted = false, first_failed_at = null, attempts = 0, last_error = null
where parked_at is not null and published_at is null
  and event_id = '<event id>';
```

Manual park (operator only, never the relay): a row stuck on a non-payload
error that the owning squad decides to set aside, for example a topic that
will not be granted. Record the decision here; the replay above (next to it)
puts the row back:

```sql
-- inspect the stuck head of the queue (the error itself is in the relay log)
select event_id, topic, aggregate_id, created_at
from sc_cmp_evidence.outbox_event
where published_at is null and parked_at is null
order by created_seq
limit 1;

-- park it by hand (park_counted stays false: the relay counts it once as OperatorPark)
update sc_cmp_evidence.outbox_event
set parked_at = now(), last_error = 'manual: <reason>'
where published_at is null and parked_at is null
  and event_id = '<event id>';

-- replay it later: the un-park statement above
```

Run these as the migration owner or the runtime role (both may update
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
- [x] Ingress from payments, istio-ingress and observability is owned by the service-mesh repository (mesh #11, 128e19d, allow-ingress-from-payments in namespace `compliance`); the chart ships no NetworkPolicy and CI rejects one
- [ ] Payment services call this API (follow-up in the payments repositories)
- [ ] Cut-over precondition: `sanctionsHit`, `pep` and `kycVerified` have a real source, not caller constants (parity and test runs may use constants)
- [x] Screening events written through a transactional outbox in the screening's transaction; one event per new screening, none on retries or when a concurrent duplicate loses (`ComplianceServiceIT`); screening inputs not published
- [x] Outbox failures per ADR-021 decision 4: payload errors park the row (skipped, counted once by `outbox_parked_events_total`, shown by `outbox_parked_rows`, replayed by hand); any other failure stops the batch without marking the row, backs off and alerts on `outbox_oldest_pending_age_seconds`, never parks; one sender at a time (`OutboxRelayTest`, `ComplianceServiceIT`)
- [ ] AsyncAPI catalog entry (proposed in asyncapi-catalog PR #11, not merged) matches `api/asyncapi/svc-cmp-evidence.yaml` (provider copy changes `screeningId` from `format: uuid` to the `CMP-<uuid>` pattern)
- [ ] Topic `evt.cmp.compliance.v1` created on the platform cluster (producer only, so no DLQ; not the retired per-event `evt.cmp.compliance.screened.v1`); IRSA `msk_cluster_arn` set
- [ ] Flyway V11 applied: unsent outbox rows written before it name `evt.cmp.compliance.v1` (`select topic, count(*) from sc_cmp_evidence.outbox_event where published_at is null group by topic` shows only that topic)
- [ ] Mesh contract lists `msk` for `compliance-evidence-service` (allow-egress-msk generated for namespace `compliance`); until then the chart keeps the relay off
- [ ] Report generation and the review/submission workflow moved here (precondition for step 3; today only the history is mirrored)
- [ ] Plan for report files at `report_file_path` (not migrated)
- [x] Runtime role separation: grants and IT in place (V7 grants, Flyway `DB_MIGRATION_*` credentials, Helm migration secret, Terraform secret; in the IT the runtime role cannot `UPDATE`/`DELETE`/`TRUNCATE` evidence or disable the trigger, `ComplianceServiceIT`); effective in an environment only after the DBA bootstrap and the migration Job have run there. The owner credential is still mounted in the service pods (open)
- [ ] DBA bootstrap of `compliance_evidence_owner` and `compliance_evidence_app` per environment, secrets filled
- [ ] Flyway moved out of the pods into a migration Job, so the pods no longer hold the owner credential
- [ ] Production backfill and reconciliation report attached here
