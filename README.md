# fintechbankx-riskcompliance-compliance-evidence-core

Bu repository, FinTechBankX DDD/EDA dönüşümünde **svc-cmp-evidence** servis yetkinliğinin kaynak kodunu, kontratlarını ve operasyonel guardrail'lerini içerir.

## Sorumluluk ve Sahiplik
| Alan | Değer |
|---|---|
| Organizasyon Modeli | Spotify Model (Tribe/Squad) |
| Tribe | Risk & Compliance Tribe |
| Squad | Risk and Compliance Decisioning Squad |
| Repo Kümesi (Capability) | compliance |
| Service ID | svc-cmp-evidence |
| Bounded Context | compliance_evidence |
| Wave | 4 |
| Mimari Yaklaşım | DDD + Hexagonal + Event-Driven |

## Sorumluluk Sınırları
- Bu repo kendi bounded context domain modelinin tek yetkili sahibidir.
- Domain kuralları altyapıdan bağımsız tutulur; entegrasyonlar port/adapter katmanında yönetilir.
- API/Event kontratları geriye dönük uyumluluk kontrolleri ile korunur.
- Güvenlik guardrail'leri (mTLS, token doğrulama, idempotency, log hijyeni) CI/CD ile zorlanır.

## Kapsam
### In Scope
- compliance_evidence bağlamına ait uygulama kodu, testler ve otomasyon.
- Bu servise ait OpenAPI/AsyncAPI veya şema artefaktları.
- Bu servisin çalışma zamanı operasyonları (gözlemlenebilirlik, release, rollback).

### Out of Scope
- Diğer bounded context'lerin iş kuralları ve veri sahipliği.
- Paylaşımlı DB anti-pattern'i; cross-context doğrudan tablo erişimi.
- Platform dışı gizli bilgi/anahtar yönetimi (merkezi policy dışında local hardcode).

## Mühendislik Standartları
- **TDD öncelikli** geliştirme, birim test + entegrasyon testi.
- **Clean Architecture**: Domain katmanı framework bağımsız.
- **12-Factor** ve environment-driven configuration.
- **FAPI odaklı güvenlik** (OIDC/OAuth2, mTLS, DPoP gereksinimleri ilgili servislerde).
- **PII güvenliği**: loglarda maskeleme, secret'ların source/env içine yazılmaması.

## Branching ve Release Akışı
- Uzun ömürlü branch'ler: `main`, `dev`, `staging`, `local`.
- Feature branch kuralı: `codex/<kisa-aciklama>`.
- Release yaklaşımı: PR + required status checks + tag tabanlı sürümleme.

## Run, test and deploy

| What | Command / path |
|---|---|
| Tests, ArchUnit rules and coverage gates | `./gradlew check` (integration tests need `TEST_DB_URL` or Docker) |
| Run locally | Migrate first, then start: `SPRING_DATASOURCE_PASSWORD=... ./gradlew :compliance-bootstrap:bootRun --args=migrate`, then the same without `--args`. The service only validates the schema (`compliance.database.flyway=validate`, decision 0002), so on an empty database it refuses to start with `Schema sc_cmp_evidence doesn't exist yet` until the migrate step has run; every local or ephemeral environment (a laptop, a review environment, a Docker container: `docker run ... compliance-evidence-service migrate`) runs it before the app, once per new migration. The relay is off by default; set `OUTBOX_RELAY_ENABLED=true` with Kafka on `localhost:9092` to publish |
| Database migrations | `compliance-infrastructure/src/main/resources/db/migration` (schema `sc_cmp_evidence`), applied in the cluster by the chart's Helm `pre-install`/`pre-upgrade` migration Job as the schema owner; the service pods only validate ([decision 0002](docs/architecture/decisions/0002-flyway-runs-in-a-migration-job.md)) |
| Container image | `docker build -t compliance-evidence-service .` |
| Kubernetes | `deploy/helm/compliance-evidence-service` (no NetworkPolicy, mesh policy or SecretStore: ingress from payments is owned by the service-mesh repository, mesh #11 128e19d; CI rejects them in the chart) |
| AWS infrastructure | `deploy/terraform` |
| Data split from the monolith | [RUNBOOK-EXTRACT-cmp-evidence](docs/migration/RUNBOOK-EXTRACT-cmp-evidence.md) |
| Deployment and Well-Architected mapping | [DEPLOYMENT_AND_WELL_ARCHITECTED](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |

Module layout: `compliance-domain` (screening result, rules, events, ports) ← `compliance-application` (use case) ← `compliance-infrastructure` (JPA, transactional outbox, web, security) ← `compliance-bootstrap` (Spring Boot app).
Packages follow the service guardrails (ADR-028): use case and command in `domain.port.in`, repository and publisher ports in `domain.port.out`, web DTOs in `infrastructure.web.dto`. `compliance-bootstrap/src/test/java/com/bank/compliance/HexagonalArchitectureTest.java` enforces the four ArchUnit rules on `./gradlew check`.

### Published events

Contract: [`api/asyncapi/svc-cmp-evidence.yaml`](api/asyncapi/svc-cmp-evidence.yaml) (provider copy). Status: Proposed. The catalog entry for this contract is proposed in fintechbankx-governance-api-contracts-asyncapi-catalog PR #11 (not merged), and the topic is not yet created on the platform cluster.

One topic per aggregate (ADR-019): every event of the screening aggregate goes to `evt.cmp.compliance.v1`, keyed by the aggregate id, so one screening's events stay in order in one partition. The event is named by its `eventType`, in the envelope and in the record header. Every record carries the UTF-8 headers `eventType`, `eventId` and `correlationId` (equal to the envelope), plus `traceparent` when the writing request was traced. No `x-fapi-interaction-id` header: this API is internal, not a FAPI flow (ADR-019 section 3). Consumers read the `eventType` header, handle the types they subscribe to and skip any other type (commit the offset, never fail or dead-letter it), so a new event type on the topic is additive. A breaking change to one event is a new eventType (`...v2`) on the same topic, published alongside the old one until consumers move; the topic major changes only for a key, partition-count or cleanup-policy change.

| Topic | published_events (eventType) | Key | When |
|---|---|---|---|
| `evt.cmp.compliance.v1` | `Compliance.ComplianceScreening.Screened.v1` | screening id (`CMP-<uuid>`) | a transaction is screened for the first time; a retry returns the stored result and publishes nothing |

This service consumes no events (no consumed_events, no consumer group, no DLQ).

Screening facts (amount, currency, sanctions/KYC/PEP flags) are **caller-attested**, all required (an omitted or null currency or flag is a 400; no currency is assumed), and stored with each result together with the rule set version; see [decision 0001](docs/architecture/decisions/0001-screening-facts-are-caller-attested.md). Results are insert-only for the runtime role (V4 trigger, V7 grants); this needs separate owner and runtime roles in each environment (see the runbook), and only the migration Job holds the owner's credential (decision 0002).

Events go through a transactional outbox (`sc_cmp_evidence.outbox_event`, written in the same database transaction as the screening) and are relayed to Kafka (Amazon MSK, IAM auth) by `OutboxRelay`. Payloads carry ids, the decision and the screening time (reason codes are restricted and stay in the POST response); the screening inputs (sanctions, PEP and KYC flags, amount) are not published. Runtime settings: `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SECURITY_PROTOCOL`, `SPRING_PROFILES_ACTIVE=kafka-msk` on AWS (`kafka-strimzi` in-cluster), `OUTBOX_RELAY_ENABLED` (application default `false`). Backlog metric: `outbox_pending_events{service="svc-cmp-evidence"}`; parked rows: counter `outbox_parked_events_total{exception}` (each parked row counted once; an operator park as `OperatorPark`; alert OutboxEventsParked on any increase over 15m, severity warning, no `for` clause; operator parks also fire it) and gauge `outbox_parked_rows` (payload errors only, per ADR-021 decision 4; any other failure stops the relay without marking the row and is retried with backoff, never parked; replay per the runbook); backlog age: `outbox_oldest_pending_age_seconds` (alert OutboxRelayStalled: `max(outbox_oldest_pending_age_seconds{service_id="svc-cmp-evidence"}) > 900` for 5m, severity critical; alert OutboxSendFailures: any increase in `outbox_send_failures_total` over 10m, severity warning; all three alerts live in fintechbankx-platform-observability-sre-operations PR #11 (not merged, commit `eca7aa0`), which also widens the AMP keep regex to the `outbox_` series; they are keyed by the `service_id` pod label and routed by squad (`compliance`); this service ships no alert rule; `service_id` comes from the pod label `fintechbankx.io/service-id`, scraped through the pod annotations `prometheus.io/scrape`, `prometheus.io/port` and `prometheus.io/path`; see the runbook); failed sends: `outbox_send_failures_total{exception=...}`. Every meter carries the common tags `app` (the service account, `compliance-evidence-service`) and `squad` (`compliance`), from `METRICS_APP` and `METRICS_SQUAD` set by the chart. The chart ships `OUTBOX_RELAY_ENABLED` "false" until the namespace has MSK egress.

AsyncAPI breaking changes: `scripts/ci/asyncapi/asyncapi-breaking.mjs` (required `ci/test`, run with `ASYNCAPI_DIR=api/asyncapi BASE_REF=origin/main` from the repository root) compares `api/asyncapi/*.yaml` with `origin/main` under ADR-019 section 5. `asyncapi-breaking.mjs` and `lib/asyncapi-model.mjs` are copied unchanged from fintechbankx-governance-api-contracts-asyncapi-catalog at 44837cc (topics `evt.<ctx>.<aggregate>.v<N>`, ADR-019) until platform adds the gate to the shared CI template. Accepted findings go in `api/asyncapi/<spec-name>.accepted-breaking.txt`, only with a migration plan: a new event major (eventType `...v2`) published on the same topic alongside the old one, or, for a key, partition-count or cleanup-policy change, a new topic major with dual-publish. While the spec is not on the catalog's `main` it stays 1.0.0.

## Dokümantasyon ve Referanslar
- [Enterprise Architecture Hub](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture)
- [Secure Microservices Architecture](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/architecture/overview/SECURE_MICROSERVICES_ARCHITECTURE.md)
- [Service Data Ownership Matrix](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_DATA_OWNERSHIP_MATRIX.md)
- [Service API Contracts Index](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/SERVICE_API_CONTRACTS_INDEX.md)
- [Transformation Plan](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/enterprisearchitecture/implementation-development/MICROSERVICES_TRANSFORMATION_PLAN.md)
- [Capability Map (PUML)](https://github.com/COPUR/fintechbankx-governance-architecture-enablement-enterprise-architecture/blob/main/docs/puml/service-mesh/enterprise-capability-map.puml)
- [Bu Repo Dokümantasyonu](./docs)

## Güvenlik ve Uyumluluk Notları
- Gerçek secret değerleri repo veya `.env` içinde tutulmaz.
- Secret üretim/rotasyon olayları merkezi log/SIEM'e taşınır.
- CI pipeline, anonimlik ve local-path sızıntısı kontrollerini bloklayıcı olarak çalıştırır.

## Katkı
- Katkı süreci için `CONTRIBUTING.md` ve squad runbook'ları izlenmelidir.
- PR'larda mimari kararlar ADR veya backlog referansı ile ilişkilendirilmelidir.

<!-- cell-architecture-start -->
## Cell-Based Architecture

This repository participates in the FinTechBankX cell-based resilience program.

- Plan: docs/architecture/CELL_BASED_ARCHITECTURE_IMPLEMENTATION_PLAN.md
- Backlog: docs/project-management/CELL_ARCHITECTURE_BACKLOG_BOARD.md
<!-- cell-architecture-end -->
