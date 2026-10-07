# Deployment and AWS Well-Architected mapping

How `svc-cmp-evidence` runs on AWS, and which file implements each
Well-Architected concern. Claims here point at code; anything not listed is
not done yet.

## Runtime shape

```
payment services ─HTTP─▶ compliance-evidence-service pods (EKS, 3..12, HPA)
                            └─ JDBC ─▶ Aurora PostgreSQL Serverless v2 (Multi-AZ)
```

| Artifact | Path |
|---|---|
| Image | `Dockerfile` (layered Spring Boot jar, JRE 23, uid 10001) |
| Kubernetes | `deploy/helm/compliance-evidence-service` (`values.yaml` prod-shaped, `values-dev.yaml`) |
| AWS | `deploy/terraform` (Aurora, KMS, Secrets Manager, IRSA, alarms; platform `microservice-base` module) |
| Runtime config | `compliance-bootstrap/src/main/resources/application.yml` (all environment values from env) |
| CI proof | `.github/workflows/deployability.yml` |

## Well-Architected pillars

| Pillar | What is in place | Where |
|---|---|---|
| Operational excellence | Health groups for startup/liveness/readiness on a separate management port; Prometheus metrics with `service` tag; correlation id (`x-fapi-interaction-id`) in logs and responses; IaC for every AWS resource | `application.yml`, `CorrelationIdFilter`, `deploy/terraform` |
| Security | OAuth2 resource server with Keycloak realm roles and method security (screening: `SERVICE`, `COMPLIANCE_OFFICER`, `ADMIN`; reading: those plus `AUDITOR`); non-root, read-only root filesystem, all capabilities dropped; DB credential from Secrets Manager via External Secrets; KMS-encrypted storage, snapshots, logs and secrets; TLS enforced (`rds.force_ssl`); IRSA least privilege; DB reachable only from the workload security group | `SecurityConfiguration`, `ComplianceController`, `deployment.yaml`, `externalsecret.yaml`, `main.tf` |
| Reliability | Aurora Multi-AZ, PITR, deletion protection; pods spread across zones, PDB, graceful shutdown; screening results are insert-only and unique per transaction, so retries are safe and return the original result | `main.tf`, `deployment.yaml`, `pdb.yaml`, `ComplianceScreeningService`, `V1__create_compliance_screening.sql` |
| Performance efficiency | Stateless pods scaled by HPA; Aurora Serverless v2; virtual threads; unique index for the transaction lookup, a customer index for evidence queries and a partial index for the manual-review queue | `hpa.yaml`, `main.tf`, `application.yml`, `V1__create_compliance_screening.sql` |
| Cost optimization | Serverless v2 floor of 0.5 ACU in dev; dev overrides (single Aurora instance, 2-4 pods); log retention 30 days outside prod | `environments/dev.tfvars.example`, `values-dev.yaml` |
| Sustainability | Scale-down to the minimum footprint outside peak; layered image keeps rebuilds small | `hpa.yaml`, `Dockerfile` |

## Known gaps

- No caller uses the service yet; payments still screen locally.
- No compliance events (no AsyncAPI contract yet).
- Screening evidence has no retention or archival job yet; regulatory retention periods still need to be set.
- The application DB role (`compliance_evidence_app`) is created by a DBA bootstrap step, not by Terraform.
- `microservice-base` is referenced at `ref=main`; pin a tag once the modules repo publishes releases.
- No load test yet; HPA targets are starting values.
