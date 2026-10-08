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
| Unit and integration tests | `./gradlew test` (integration tests need `TEST_DB_URL` or Docker) |
| Run locally | `SPRING_DATASOURCE_PASSWORD=... ./gradlew :compliance-bootstrap:bootRun` (Kafka on `localhost:9092`, or `OUTBOX_RELAY_ENABLED=false`) |
| Database migrations | `compliance-infrastructure/src/main/resources/db/migration` (schema `sc_cmp_evidence`) |
| Container image | `docker build -t compliance-evidence-service .` |
| Kubernetes | `deploy/helm/compliance-evidence-service` |
| AWS infrastructure | `deploy/terraform` |
| Data split from the monolith | [RUNBOOK-EXTRACT-cmp-evidence](docs/migration/RUNBOOK-EXTRACT-cmp-evidence.md) |
| Deployment and Well-Architected mapping | [DEPLOYMENT_AND_WELL_ARCHITECTED](docs/architecture/DEPLOYMENT_AND_WELL_ARCHITECTED.md) |

Module layout: `compliance-domain` (screening result, rules, events, ports) ← `compliance-application` (use case) ← `compliance-infrastructure` (JPA, transactional outbox, web, security) ← `compliance-bootstrap` (Spring Boot app).

### Published events

Contract: [`api/asyncapi/svc-cmp-evidence.yaml`](api/asyncapi/svc-cmp-evidence.yaml) (provider copy; the AsyncAPI catalog mirrors it). Status: Proposed.

| published_events | Event type | Key | When |
|---|---|---|---|
| `evt.cmp.compliance.screened.v1` | `Compliance.ComplianceScreening.Screened.v1` | screening id (`CMP-<uuid>`) | a transaction is screened for the first time; a retry returns the stored result and publishes nothing |

Events go through a transactional outbox (`sc_cmp_evidence.outbox_event`, written in the same database transaction as the screening) and are relayed to Kafka (Amazon MSK, IAM auth) by `OutboxRelay`. Payloads carry ids, the decision, reason codes and the screening time; the screening inputs (sanctions, PEP and KYC flags, amount) are not published. Runtime settings: `KAFKA_BOOTSTRAP_SERVERS`, `KAFKA_SECURITY_PROTOCOL`, `SPRING_PROFILES_ACTIVE=msk` on AWS, `OUTBOX_RELAY_ENABLED`. Backlog metric: `outbox_pending_events{service="svc-cmp-evidence"}`.

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
