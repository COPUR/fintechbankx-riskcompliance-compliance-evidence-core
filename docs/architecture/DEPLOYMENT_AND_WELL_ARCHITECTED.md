# Deployment and AWS Well-Architected mapping

How `svc-cmp-evidence` runs on AWS, and which file implements each
Well-Architected concern. Claims here point at code; anything not listed is
not done yet.

## Runtime shape

```
payment services ─HTTP─▶ compliance-evidence-service pods (EKS namespace compliance, 3..12, HPA)
                            ├─ JDBC ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
                            └─ outbox relay ─▶ Amazon MSK (IAM auth) evt.cmp.compliance.screened.v1
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/compliance-evidence-service` (`values.yaml` prod-shaped, `values-dev.yaml`) |
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA incl. topic-scoped MSK IAM access, alarms; platform `microservice-base` module) |
| Events | `api/asyncapi/svc-cmp-evidence.yaml` (Proposed) |
| Runtime config | `compliance-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with `service` tag; outbox backlog gauge `outbox_pending_events` and parked-event gauge `outbox_parked_events` (alert above 0); backlog age `outbox_oldest_pending_age_seconds`; correlation id (`x-fapi-interaction-id`) in logs, responses and events; IaC for every AWS resource | `application.yml`, `OutboxConfiguration`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server with Keycloak realm roles and method security; tokens must come from the realm issuer and carry `aud` `svc-cmp-evidence` (`OIDC_AUDIENCE`, startup fails if blank); the `SERVICE` role counts only for clients (`azp`) on `SERVICE_CALLERS` (default `svc-pay-initiation-settlement`); DPoP is not required for internal callers (mesh-wide STRICT mTLS, platform contract addendum); NetworkPolicy admits only `payments`, `istio-ingress` and (management port) `observability` (screening: `SERVICE`, `COMPLIANCE_OFFICER`, `ADMIN`; reading: those plus `AUDITOR`); non-root, read-only root filesystem, all capabilities dropped; DB credential from Secrets Manager via External Secrets; KMS-encrypted storage, snapshots, logs and secrets; TLS enforced (`rds.force_ssl`); IRSA least privilege (MSK only: connect, and write only to `evt.cmp.compliance.*`; the DB credential arrives through External Secrets under `<env>/compliance-evidence-service/`, KMS key tagged `fintechbankx.io/secrets`); Kafka over SASL_SSL with IAM auth; DB reachable only from the workload security group; events carry ids, decision and reason codes only, never the screening inputs | `SecurityConfiguration`, `ServiceCallerPolicy`, `ComplianceController`, `networkpolicy.yaml`, `deployment.yaml`, `externalsecret.yaml`, `main.tf`, `ComplianceEventEnvelopeFactory` |
| Reliability | Aurora Multi-AZ, PITR, deletion protection; pods spread across zones, PDB, graceful shutdown; screening results are insert-only and unique per transaction, so retries are safe and return the original result; transactional outbox (result and event commit together; a losing concurrent duplicate leaves neither), ordered single-relay publishing, idempotent Kafka producer with `max.block.ms` 10 s; rows that can never be sent are parked instead of stalling the relay (non-retriable errors at once; retriable ones only after 24 h of failing, so an outage parks nothing); the advisory lock lets one replica send at a time (concurrency IT) | `main.tf`, `deployment.yaml`, `pdb.yaml`, `ComplianceScreeningService`, `TransactionalComplianceScreening`, `OutboxRelay`, `V1__create_compliance_screening.sql`, `V3__create_outbox.sql` |
| Performance efficiency | Stateless pods scaled by HPA on CPU only; Aurora Serverless v2; virtual threads; unique index for the transaction lookup, a customer index for evidence queries and a partial index for the manual-review queue; partial index for the outbox queue | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_compliance_screening.sql`, `V3__create_outbox.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); log retention 30 days outside prod; published outbox rows purged after 7 days | `environments/dev.tfvars.example`, `values-dev.yaml`, `OutboxRelay.purgePublished` |
| Sustainability | Scale-down to the minimum footprint outside peak; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Turning on the outbox relay

The chart ships `OUTBOX_RELAY_ENABLED: "false"`. Preconditions before turning it on:

1. The mesh contract (`fintechbankx-platform-mesh-security-service-mesh`, `contracts/mesh-contract.yaml`) lists `msk` for `compliance-evidence-service`, so allow-egress-msk is generated for namespace `compliance`; without it the default-deny egress drops the relay's connection to MSK port 9098.
2. Topic `evt.cmp.compliance.screened.v1` exists on MSK (producer only, so no DLQ), and `msk_cluster_arn` is set in Terraform.

Then deploy with `--set-string config.OUTBOX_RELAY_ENABLED=true` (or set it in the environment's values file). `outbox_pending_events` should drain to 0 and `outbox_parked_events` stay 0; parked rows are replayed by hand (runbook, "Parked outbox events").

## Known gaps

- No caller uses the service yet; payments still screen locally.
- No consumer of `evt.cmp.compliance.screened.v1` yet, and the topic is not yet created on the platform cluster.
- The mesh contract gives namespace `compliance` no MSK egress yet, so the relay ships off (see above).
- Screening evidence has no retention or archival job yet; regulatory retention periods still need to be set.
- The DB roles (owner `compliance_evidence_owner`, runtime `compliance_evidence_app`) are created by a DBA bootstrap step, not by Terraform; Terraform creates their secrets (`db-migration`, `db-app`).
- Flyway still runs in the pods, so they hold the owner credential too; a migration Job is the follow-up.
- `microservice-base` is pinned to a commit; move to a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
