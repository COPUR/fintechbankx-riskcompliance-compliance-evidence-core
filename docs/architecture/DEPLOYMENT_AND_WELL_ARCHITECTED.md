# Deployment and AWS Well-Architected mapping

How `svc-cmp-evidence` runs on AWS, and which file implements each
Well-Architected concern. Claims here point at code; anything not listed is
not done yet.

## Runtime shape

```
helm install/upgrade ─▶ pre-install/pre-upgrade Job compliance-evidence-service-db-migration
                            └─ JDBC (schema owner, Flyway migrate) ─▶ Aurora; must succeed before any pod rolls out
payment services ─HTTP─▶ compliance-evidence-service pods (EKS namespace compliance, 3..12, HPA)
                            ├─ JDBC (runtime role, Flyway validate at startup) ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
                            └─ outbox relay ─▶ Amazon MSK (IAM auth) evt.cmp.compliance.v1
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/compliance-evidence-service` (`values.yaml` prod-shaped, `values-dev.yaml`; Flyway in the hook Job `templates/migration-job.yaml`, [decision 0002](decisions/0002-flyway-runs-in-a-migration-job.md)) |
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA incl. topic-scoped MSK IAM access, alarms; platform `microservice-base` module) |
| Events | `api/asyncapi/svc-cmp-evidence.yaml` (Proposed) |
| Runtime config | `compliance-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with common tags `app` and `squad` (and `service`); failed sends `outbox_send_failures_total{exception}`; outbox backlog gauge `outbox_pending_events` and parked-event counter `outbox_parked_events_total` (platform alert OutboxEventsParked) and gauge `outbox_parked_rows`; backlog age `outbox_oldest_pending_age_seconds`; correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server with Keycloak realm roles and method security; tokens must come from the realm issuer and carry `aud` `svc-cmp-evidence` (`OIDC_AUDIENCE`, startup fails if blank); the `SERVICE` role counts only for clients (`azp`) on `SERVICE_CALLERS` (default `svc-pay-initiation-settlement`); DPoP is not required for internal callers (mesh-wide STRICT mTLS, platform contract addendum); ingress from `payments`, `istio-ingress` and (management port) `observability` is owned by the service-mesh repository (mesh #11, 128e19d; the chart ships no NetworkPolicy) (screening: `SERVICE`, `COMPLIANCE_OFFICER`, `ADMIN`; reading: those plus `AUDITOR`); non-root, read-only root filesystem, all capabilities dropped (the migration Job too); the service pods hold only the runtime role's DB credential (insert-only evidence, V7) and validate the schema at startup; the schema owner's credential is mounted only by the Helm pre-install/pre-upgrade migration Job (own ServiceAccount without IAM role) and exists in the namespace only while it runs (decision 0002, `check-migration-job.py`); DB credentials from Secrets Manager via External Secrets; KMS-encrypted storage, snapshots, logs and secrets; TLS enforced (`rds.force_ssl`) and verified by the client (`DB_URL` `sslmode=verify-full` against the RDS CA bundle from ConfigMap `rds-ca-bundle`, mounted read-only; the chart refuses a PostgreSQL URL without it); IRSA least privilege (MSK only: connect, and write only to `evt.cmp.compliance.*`; the DB credential arrives through External Secrets under `<env>/compliance-evidence-service/`, encrypted with a secrets-only KMS key tagged `fintechbankx.io/secrets`; the Aurora storage, snapshot and Performance Insights key is a separate, untagged key the External Secrets role cannot decrypt, ADR-023); Kafka over SASL_SSL with IAM auth; DB reachable only from the workload security group; events carry ids and the decision only (no reason codes), never the screening inputs | `SecurityConfiguration`, `ServiceCallerPolicy`, `ComplianceController`, `deployment.yaml`, `migration-job.yaml`, `externalsecret.yaml`, `DatabaseMigration`, `FlywayStartupConfiguration`, `main.tf`, `ComplianceEventEnvelopeFactory` |
| Reliability | Aurora Multi-AZ, PITR, deletion protection; schema migrated by a hook Job before the rollout (a failed Job fails the release and leaves the running pods alone; new pods refuse to start on a pending migration); pods spread across zones, PDB, graceful shutdown; screening results are insert-only and unique per transaction, so retries are safe and return the original result; transactional outbox (result and event commit together; a losing concurrent duplicate leaves neither), ordered single-relay publishing, idempotent Kafka producer with `max.block.ms` 10 s; per ADR-021 decision 4 only payload errors park a row; outages, auth errors and anything unclassified stop the batch without marking the row, back off (in memory, up to 5 min) and alert (proposed to platform observability, keyed by `service_id`) on `outbox_oldest_pending_age_seconds` above 900 s, never parked; the advisory lock lets one replica send at a time (concurrency IT) | `main.tf`, `deployment.yaml`, `pdb.yaml`, `ComplianceScreeningService`, `TransactionalComplianceScreening`, `OutboxRelay`, `V1__create_compliance_screening.sql`, `V3__create_outbox.sql` |
| Performance efficiency | Stateless pods scaled by HPA on CPU only; Aurora Serverless v2; virtual threads; unique index for the transaction lookup, a customer index for evidence queries and a partial index for the manual-review queue; partial index for the outbox queue | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_compliance_screening.sql`, `V3__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); log retention 30 days outside prod; published outbox rows purged after 7 days | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Turning on the outbox relay

The chart ships `OUTBOX_RELAY_ENABLED: "false"`. Preconditions before turning it on:

1. The mesh contract (`fintechbankx-platform-mesh-security-service-mesh`, `contracts/mesh-contract.yaml`) lists `msk` for `compliance-evidence-service`, so allow-egress-msk is generated for namespace `compliance`; without it the default-deny egress drops the relay's connection to MSK port 9098.
2. Topic `evt.cmp.compliance.v1` exists on MSK (one topic per aggregate, ADR-019; producer only, so no DLQ), and `msk_cluster_arn` is set in Terraform. Records are keyed by the screening id and carry the headers `eventType`, `eventId` and `correlationId` (plus `traceparent` when traced); consumers skip eventTypes they do not handle.

Then deploy with `--set-string config.OUTBOX_RELAY_ENABLED=true` (or set it in the environment's values file). `outbox_pending_events` should drain to 0 and `outbox_parked_rows` stay 0; parked rows are replayed by hand (runbook, "Parked outbox events").

## Known gaps

- No caller uses the service yet; payments still screen locally.
- No consumer of `evt.cmp.compliance.v1` yet, and the topic is not yet created on the platform cluster (the platform's provisioning list must carry `evt.cmp.compliance.v1`, not the retired per-event topic).
- The mesh contract gives namespace `compliance` no MSK egress yet, so the relay ships off (see above).
- Screening evidence has no retention or archival job yet; regulatory retention periods still need to be set.
- The DB roles (owner `compliance_evidence_owner`, runtime `compliance_evidence_app`) are created by a DBA bootstrap step, not by Terraform; Terraform creates their secrets (`db-migration`, `db-app`).
- The migration Job is checked by rendering, kubeconform and CI mutation checks only; it has not run on a cluster yet (External Secrets sync timing, native Istio sidecar completion).
- `microservice-base` is pinned to a commit; move to a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
