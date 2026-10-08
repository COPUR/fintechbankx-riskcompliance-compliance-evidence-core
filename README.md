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
| Run locally | `SPRING_DATASOURCE_PASSWORD=... ./gradlew :compliance-bootstrap:bootRun` (the relay is off by default; set `OUTBOX_RELAY_ENABLED=true` with Kafka on `localhost:9092` to publish) |
| Database migrations | `compliance-infrastructure/src/main/resources/db/migration` (schema `sc_cmp_evidence`) |
| Container image | `docker build -t compliance-evidence-service .` |
| Kubernetes | `deploy/helm/compliance-evidence-service` (no NetworkPolicy, mesh policy or SecretStore: ingress from payments is owned by the service-mesh repository, mesh #11 128e19d; CI rejects them in the chart) |
| AWS infrastructure | `deploy/terraform` |
| Data split from the monolith | [RUNBOOK-EXTRACT-cmp-evidence](docs/migration/RUNBOOK-EXTRACT-cmp-evidence.md) |
| Deployment and Well-Architected mapping | [DEPLOYMENT_AND_WELL_ARCHITECTED](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |

Module layout: `compliance-domain` (screening result, rules, events, ports) ← `compliance-application` (use case) ← `compliance-infrastructure` (JPA, transactional outbox, web, security) ← `compliance-bootstrap` (Spring Boot app).
Packages follow the service guardrails (ADR-028): use case and command in `domain.port.in`, repository and publisher ports in `domain.port.out`, web DTOs in `infrastructure.web.dto`. `compliance-bootstrap/src/test/java/com/bank/compliance/HexagonalArchitectureTest.java` enforces the four ArchUnit rules on `./gradlew check`.

### Published events

Contract: [`api/asyncapi/svc-cmp-evidence.yaml`](api/asyncapi/svc-cmp-evidence.yaml) (provider copy). Status: Proposed. The catalog entry for this contract is proposed in fintechbankx-governance-architecture-enablement-asyncapi-catalog PR #11 (not merged), and the topics are not yet created on the platform cluster.

| published_events | Event type | Key | When |
|---|---|---|---|
| `evt.cmp.compliance.screened.v1` | `Compliance.ComplianceScreening.Screened.v1` | screening id (`CMP-<uuid>`) | a transaction is screened for the first time; a retry returns the stored result and publishes nothing |

Screening facts (amount, currency, sanctions/KYC/PEP flags) are **caller-attested**, all required (an omitted or null currency or flag is a 400; no currency is assumed), and stored with each result together with the rule set version; see [decision 0001](docs/architecture/decisions/0001-screening-facts-are-caller-attested.md). Results are insert-only for the runtime role (V4 trigger, V7 grants); this needs separate owner and runtime roles in each environment (see the runbook).

Events go through a transactional outbox (`sc_cmp_evidence.outbox_event`, written in the same database transaction as the screening) and are relayed to Kafka (Amazon MSK, IAM auth) by `OutboxRelay`. Payloads carry ids, the decision and the screening time (reason codes are restricted and stay in the POST response; removed from the event in AsyncAPI 1.1.0); the screening inputs (sanctions, PEP and KYC flags, amount) are not published. Runtime settings: `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SECURITY_PROTOCOL`, `SPRING_PROFILES_ACTIVE=kafka-msk` on AWS (`kafka-strimzi` in-cluster), `OUTBOX_RELAY_ENABLED` (application default `false`). Backlog metric: `outbox_pending_events{service="svc-cmp-evidence"}`; parked events: `outbox_parked_events` (payload errors only, per ADR-021 decision 4; any other failure stops the relay without marking the row and is retried with backoff, never parked; alert above 0, replay per the runbook); backlog age: `outbox_oldest_pending_age_seconds` (platform alert above 900 s, owned by squad `compliance`); failed sends: `outbox_send_failures_total{exception=...}`. Every meter carries the common tags `app` (the service account, `compliance-evidence-service`) and `squad` (`compliance`), from `METRICS_APP` and `METRICS_SQUAD` set by the chart. The chart ships `OUTBOX_RELAY_ENABLED` "false" until the namespace has MSK egress.

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
