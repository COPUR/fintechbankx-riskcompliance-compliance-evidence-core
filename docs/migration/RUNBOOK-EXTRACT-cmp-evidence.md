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

Flyway migrations: `compliance-infrastructure/src/main/resources/db/migration/V1__create_compliance_screening.sql` to `V12__grant_runtime_role_read_on_schema_history.sql`. They are applied by the chart's migration Job, never by the service pods (section "Database migrations (Flyway Job)"). The service never reads monolith tables and the monolith must not read `sc_cmp_evidence`.

Screening results store the facts they were decided on (amount, currency, sanctions/KYC/PEP flags, rule set version), where they came from (`attestation_source`: `CALLER_ATTESTED` for a listed service, `STAFF_ATTESTED` for a compliance officer or administrator) and who stated them (`attested_by`: the token `azp` of the service or `sub` of the staff member; V6). The flags come from the caller ([decision 0001](../architecture/decisions/0001-screening-facts-are-caller-attested.md)). A replay from another caller is a 409.

### Database roles (DBA bootstrap)

| Role | Used by | Privileges |
|---|---|---|
| migration owner (`compliance_evidence_owner`, secret `<env>/compliance-evidence-service/db-migration`) | the Flyway migration Job only (`DB_MIGRATION_USERNAME` / `DB_MIGRATION_PASSWORD`, Helm `externalSecret.migrationSecretName`, required); the service pods never mount it | owns `sc_cmp_evidence` (created by `db/bootstrap/roles.sql`) and its objects; `CONNECT` on the database, no `CREATE` on it |
| `compliance_evidence_app` (runtime, `DB_USERNAME`, secret `<env>/compliance-evidence-service/db-app`) | the service | granted by V7 (Flyway placeholder `runtime_role`): `USAGE` on the schema; `SELECT, INSERT` on `compliance_screening` (no `UPDATE`, `DELETE`, `TRUNCATE`); `SELECT, INSERT, UPDATE, DELETE` on `outbox_event` (the relay marks, parks and purges rows); `SELECT` on `legacy_compliance_report`; by V12 `SELECT` on `flyway_schema_history` (the service validates at startup). Not the owner, so it cannot `ALTER`/`DROP` the tables or disable the V4 trigger (`ComplianceServiceIT.theRuntimeRoleCannotChangeOrRemoveEvidence`), nor record a migration |
| backfill role | `db/backfill/run-backfill.sh` | `CREATE` on the database (staging schema); `SELECT, INSERT, UPDATE, DELETE` on `legacy_compliance_report` only |

`compliance_screening` is insert-only evidence for the runtime role: V7 grants it only `SELECT, INSERT`, and the `tr_compliance_screening_insert_only` trigger (V4) refuses `UPDATE` and `DELETE`. The table owner (the migration role) can still disable the trigger or `TRUNCATE`. Its credential is mounted only by the migration Job, and the hook deletes it from the namespace after a successful run ([decision 0002](../architecture/decisions/0002-flyway-runs-in-a-migration-job.md)). The guarantee therefore holds once the DBA bootstrap has created separate roles in the environment, and the service asserts it at every start (`RuntimeRoleGuard`, on its own pool after Flyway's validate; on by default like the TLS assertions and off only with the `local` profile, because a single-user local run is its own owner; the test switch leaves it on and the integration tests run it as the least-privilege runtime role): as `current_user` it must not be a member of the owner of `compliance_screening` nor of the owner of the schema (`pg_has_role`, `MEMBER`), must own no table in the schema, have no `CREATE` on it, no `UPDATE`, `DELETE`, `TRUNCATE` or `TRIGGER` on `compliance_screening` (`has_table_privilege`) and no `UPDATE` or `REFERENCES` on any of its columns (`has_any_column_privilege`; a column-level `GRANT UPDATE (decision)` is invisible to the table check); any finding keeps the pod from starting, with a message that names `DB_USERNAME` and the finding, never the role or a credential (`RuntimeRoleGuardIT`, one scratch role per privilege; `DatabaseMigrationIT`, a role granted the owner). The guard is a `SmartInitializingSingleton` registered `@Lazy(false)`: it runs once every singleton exists, before the web server, and `spring.main.lazy-initialization=true` (`SPRING_MAIN_LAZY_INITIALIZATION`, a config key the chart refuses) cannot skip it; it depends on Flyway's initializer, so the validate still runs first. Nothing yet makes tampering by a DBA detectable. Any future retention purge needs its own reviewed migration.

DBA bootstrap, per environment, before the first deploy: run `db/bootstrap/roles.sql` as the Aurora master user against `db_cmp_evidence_<env>` (`psql "<admin connection>" -v ON_ERROR_STOP=1 -f db/bootstrap/roles.sql`). It is idempotent and creates the two distinct `LOGIN` roles (`compliance_evidence_owner`, `compliance_evidence_app`; no `SUPERUSER`, `CREATEROLE`, `CREATEDB`, `REPLICATION` or `BYPASSRLS`, the runtime role no member of the owner, which it refuses on a re-run), `CONNECT` for both and the schema `sc_cmp_evidence` owned by the owner. The runtime role gets nothing else there: V7 and V12 grant it (insert-only on `compliance_screening`). The file holds no password. Set both with `\password <role>` in psql, out of band, then write their `{"username","password"}` to the two secrets Terraform creates, and set Helm `externalSecret.migrationSecretName` (the chart does not render without it). The migration Job then migrates as the owner, and V7 and V12 grant the runtime role. Local single-user runs (`./gradlew bootRun`, no `DB_MIGRATION_*`) have no Job: run `./gradlew :compliance-bootstrap:bootRun --args=migrate` (or the jar with the argument `migrate`) first, because the service only validates. V7 and V12 then change nothing. This holds for every ephemeral or local environment (laptop, review or test environment, a plain `docker run`, a CI step that boots the jar): the migrate step runs before the app starts, once per new migration, or the app refuses to start with `Schema sc_cmp_evidence doesn't exist yet` (empty database) or `FlywayValidateException` (pending migration). That refusal is the intended design (decision 0002), not a fault to work around by switching `compliance.database.flyway` to `migrate`.

`RoleBootstrapIT` (required `ci/test`) runs the file twice on a scratch PostgreSQL (the second run must change no role, membership or ACL), then every migration as the owner, and checks that the runtime role is refused `UPDATE`, `DELETE` and `TRUNCATE` on `compliance_screening` by privilege (SQLSTATE 42501, before the V4 trigger). The platform's terraform-modules ships only a generic `role_bootstrap_sql`; this file is the service's own (not verified here: the terraform-modules commit that adds it is not in the local clone).

A DBA (or the RDS master user) can always change rows; tamper evidence against them (for example a hash chain) is not built.

### Database migrations (Flyway Job)

Flyway never runs in the service pods ([decision 0002](../architecture/decisions/0002-flyway-runs-in-a-migration-job.md)). The chart's `templates/migration-job.yaml` is a Helm `pre-install` and `pre-upgrade` hook Job. It runs the service image with the argument `migrate` (`DatabaseMigration`: datasource, Flyway, `DatabaseTlsGuard` and `MigrationRoleGuard` only), migrates as the schema owner through the verified `DB_URL`, and exits. Its pods are labelled `app.kubernetes.io/name=compliance-evidence-service` (the mesh's Aurora egress) and `app.kubernetes.io/component=db-migration`, which no Service, PDB or topology spread selects (cicd-templates 335a345), and `sidecar.istio.io/inject: "false"`, written after `podLabels` so it overrides their `"true"`. The mesh keys Aurora egress on both labels (name and `component=db-migration`) and the Job needs no proxy. The mesh now runs native sidecars (service-mesh 931b6bc), with which a Job completes even with a proxy, so the opt-out is optional; it is kept because it costs nothing and protects a cluster without native sidecars, where an injected proxy would keep the pod running after Flyway exits until Helm's `--timeout`. `check-migration-job.py` asserts the label. The service pods have only the runtime role. At startup they validate the schema history and refuse to start while a migration is pending (`FlywayValidateException` in the log).

Deploy order, on `helm install` and on every `helm upgrade`:

1. Hooks at weight -10: the `compliance-evidence-service-db-migration` ExternalSecret (External Secrets syncs the owner credential into Secret `compliance-evidence-service-db-migration`) and the Job's ServiceAccount of the same name (no IAM role, no API token).
2. Hook at weight 0: Job `compliance-evidence-service-db-migration`. Its pod waits in `CreateContainerConfigError` until the Secret exists, then migrates. `backoffLimit` 1, `activeDeadlineSeconds` 600, `ttlSecondsAfterFinished` 86400 (values `migrationJob`).
3. Only after the Job succeeds: Helm deletes the hook ExternalSecret and ServiceAccount (the Secret goes with them), then creates or updates the regular resources (ConfigMap, Deployment and the others). New pods validate and start.

Use `helm upgrade --install ... --timeout 15m`. Helm's default 5 min is shorter than the Job's 600 s deadline plus the rollout, so Helm would give up while the Job is still running.

Deploy pipeline: this repository's CI is hand-rolled (`Deployability` renders and validates the chart; nothing deploys from here) and no workflow calls the cicd-templates `helm-deploy.yml` yet. When the service adopts it, the calling job passes `helm-timeout: 15m` to `helm-deploy.yml` (the same 15 min as above, so the hook Job's 600 s deadline fits) and sets its own `timeout-minutes` above that, 20 for example, so the GitHub job does not cancel Helm while the migration Job is still within its deadline.

A failed Job blocks the rollout. Helm marks the install or upgrade failed and does not touch the Deployment. On an upgrade the old pods keep serving on the old schema; on a first install nothing is deployed. Flyway applies each migration in its own transaction, so a failed migration leaves the schema at the last applied version and marks nothing as applied. To diagnose:

```bash
kubectl -n compliance get job,pod -l app.kubernetes.io/component=db-migration
kubectl -n compliance logs job/compliance-evidence-service-db-migration --all-containers
kubectl -n compliance describe job compliance-evidence-service-db-migration   # DeadlineExceeded, BackoffLimitExceeded
kubectl -n compliance get externalsecret compliance-evidence-service-db-migration   # SecretSynced?
```

Common causes: the owner secret is not filled or not synced (the pod stays in `CreateContainerConfigError` until the deadline), `rds-ca-bundle` is missing (`ContainerCreating`), Aurora egress is not granted, `DatabaseTlsGuard` refused the URL, or a migration failed (SQL error in the log).

To re-run, fix the cause and run the same `helm upgrade` again. The `before-hook-creation` policy deletes the old Job, ExternalSecret and ServiceAccount and creates new ones. The ExternalSecret and the owner Secret stay in the namespace after a failed run, until that re-run or until you delete them (`kubectl -n compliance delete externalsecret compliance-evidence-service-db-migration`). Never repair by giving the service pods the owner credential. If the history needs `flyway repair` (a failed migration on a database without transactional DDL, or a changed checksum), the DBA runs it as the owner after review, outside the cluster.

`helm rollback` runs no hook. An older image validates against a newer history: Flyway ignores applied migrations it does not know. A rollback therefore starts as long as the newer migrations were additive. A migration that an older image cannot run on is a release decision: write it expand/contract style.

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
- `config.DB_URL` is the Terraform output `jdbc_url`, which uses `sslmode=verify-full&sslrootcert=/etc/fintechbankx/rds-ca/global-bundle.pem`: the driver verifies Aurora's certificate and host name (`sslmode=require` would encrypt but trust any certificate). The chart refuses to render a `jdbc:postgresql` URL without `sslmode=verify-full`. The migration Job (schema owner) gets the same URL, the RDS CA mount and `DB_SSL_ROOT_CERT`, so both roles are verified.
- `externalSecret.migrationSecretName` is set (the chart refuses to render without it) and is another secret than `externalSecret.remoteSecretName` (the chart refuses the same name for both, compared trimmed and without a trailing `/`), and the `db-migration` secret is filled with the owner's credential, not the runtime role's: the migration entry point (`MigrationRoleGuard`, before any connection) exits 1 when `DB_MIGRATION_USERNAME` equals `DB_USERNAME`, case-insensitively, so a single credential for the Job and the service never migrates (decision 0002). The migration Job needs the secret before any pod starts (section 1, "Database migrations (Flyway Job)").
- Aurora TLS guards: the chart calls the platform datasource/TLS guard `fbx.guard` first in the Deployment and in the migration Job (`compliance.guard` in `_helpers.tpl`; `templates/_fbx_helpers.tpl` is cicd-templates 73e1881 `charts/fintechbankx-service/templates/_helpers.tpl` copied whole and unchanged without a header; the deployability job runs `scripts/ci/verify-vendored-guard.sh` from the same commit with the pinned sha256, which also refuses any other `fbx.*` define or block in the chart and any workload template that does not run the guard first). While the CA bundle is mounted (always, in this chart) the guard pins it: `databaseCa.mountPath` `/etc/fintechbankx/rds-ca`, `databaseCa.key` `global-bundle.pem` and `databaseCa.configMapName` `rds-ca-bundle` (the adapter passes all three; another value, or `null`, fails the render, so values cannot choose the database trust anchor). `config.DB_URL` starts with `jdbc:postgresql:` and carries `sslmode=verify-full` and `sslrootcert=<databaseCa.mountPath>/<databaseCa.key>` (`/etc/fintechbankx/rds-ca/global-bundle.pem`) exactly once each (lower-case names: PgJDBC reads parameter names case-sensitively, so `SSLMODE=verify-full` or `SSLROOTCERT=...` is refused by name instead of being ignored by the driver), no `sslfactory`, `sslfactoryarg`, `sslhostnameverifier` or `sslpasswordcallback` (they replace the verification), no `service` (a `pg_service.conf` entry would supply host, port and TLS settings the chart cannot see), no percent-encoded `=` or `&`, no `${` or `$(` (a placeholder or variable resolved after the check could add `sslmode=disable`), and no `config.*` key is on the refused list below; the chart refuses to render otherwise, and the service's `DatabaseTlsGuard`, on by default wherever the image runs (only the Spring profile `local` or the test switch `fintechbankx.tls.enforce=false`, set in test code only, turn the TLS startup assertions off, with a WARN in the log; the chart refuses both), refuses to start without `DB_SSL_ROOT_CERT` (missing or blank: the chart sets it from the mounted bundle on every container) and on any datasource or Flyway URL that PgJDBC would not verify against that bundle, and on a `spring.datasource.hikari.jdbc-url` or `spring.flyway.url` that is not exactly the checked `spring.datasource.url` (the pool or Flyway would connect with it instead, even when verified).
- Refused `config.*` keys. The platform guard (`fbx.guard`) refuses, in every relaxed-binding spelling (Spring Boot drops every character other than `[a-z0-9]` inside a name element): any Spring datasource, Flyway, Liquibase or R2DBC property, `spring.application.json`, any name containing `jdbc-url`, `ssl-factory`, `ssl-hostname-verifier`, `ssl-password-callback`, `ssl-root-cert` or `ssl-mode`, `DB_URL` in another spelling, every `spring.config.*`, `spring.profiles.*` and `spring.ssl.*` name, any name containing `ssl-bundle`, and `fintechbankx.tls.*` (the switch of the startup TLS assertions); every Kafka client TLS setting (`spring.kafka.ssl.*`, per-client `ssl.*` and `ssl.*` under `spring.kafka.*properties`, other than `ssl.endpoint.identification.algorithm`, which must be `https`), a `security.protocol` other than `SASL_SSL` (with `kafka.runtime` `msk`) or `SSL` (with `strimzi`), and `KAFKA_TLS_CERT`, `KAFKA_TLS_KEY`, `KAFKA_TLS_CA` in any spelling (they come only from `valueFrom.secretKeyRef` on `kafkaClientTls.secretName`, which the Deployment renders); every `spring.data.mongodb.*` name except `SPRING_DATA_MONGODB_USERNAME` and `SPRING_DATA_MONGODB_PASSWORD`, and `MONGODB_URI` in any spelling (the service uses no DocumentDB); each of these also with `_` inside a name element (`spring.kafka.s_sl.*`, `spring.pro_files.active`), which Spring Boot reads as the same property; a key that is not a ConfigMap key (`[-._a-zA-Z0-9]+`: a newline or quote could inject further keys); and checks the value of `JAVA_TOOL_OPTIONS`, `JDK_JAVA_OPTIONS` and `_JAVA_OPTIONS` (no datasource, config, profile, TLS, `kafka` or `mongodb` mention). The chart's own rule (`compliance.refusedConfigKey`) stays where the guard is narrower: each key is normalised before it is matched, upper case with every character that is not a letter or digit dropped, so a dash, dot, underscore or index anywhere in the key spells the same name (`spring.config.import[0]`, `SPRING_CONFIG_IM-PORT` and `SPRINGCONFIGIMPORT0` are all `SPRINGCONFIGIMPORT0`; `SPRING_DATA.SOURCE_URL`, which the guard's canonical form keeps apart, is `SPRINGDATASOURCEURL`):
  - `SPRINGDATASOURCE*`, `SPRINGFLYWAY*`, `SPRINGLIQUIBASE*`, `SPRINGR2DBC*`, `SPRINGAPPLICATIONJSON*` (by prefix: `SPRING_DATASOURCE_URL`, `SPRING_FLYWAY_URL`, `SPRING_LIQUIBASE_URL`, `SPRING_R2DBC_URL`, a driver class name, Hikari data-source properties, a user name): a second database URL or driver setting would replace the verified `DB_URL`; the chart sets every other database setting itself.
  - `SPRINGCONFIG*` (by prefix: `SPRING_CONFIG_IMPORT`, `SPRING_CONFIG_ADDITIONAL_LOCATION`, `SPRING_CONFIG_LOCATION`, `SPRING_CONFIG_NAME`, with or without an index): a file or configtree picked or loaded from there can set the URL. The only configtree this service may read is one the chart itself renders on the fixed mount `optional:configtree:/etc/fintechbankx/config/` (the chart renders none today); no values key can name another location.
  - `JAVATOOLOPTIONS`, `JDKJAVAOPTIONS`, `JAVAOPTIONS` (this is `_JAVA_OPTIONS` normalised), `JAVAOPTS`, whatever the value: a system property (`-Dspring.datasource.url=...`) outranks every environment variable.
  - `LOGGINGLEVEL*`: logging levels are fixed in `application.yml`; an install-time level could turn on wire logging (`org.postgresql`, `org.apache.kafka`), which prints credentials and personal data.
  - `SPRINGMAIN*` (`SPRING_MAIN_LAZY_INITIALIZATION`, `spring.main.lazy-initialization`, `spring.main.allow-bean-definition-overriding`, `SPRING_MAIN_SOURCES`, any spelling), whatever the value: `spring.main.*` changes how the context starts. Lazy initialization would skip any startup assertion that is an ordinary singleton nothing injects (the guards are now hooks it cannot skip: `BeanFactoryPostProcessor`s and an eager `SmartInitializingSingleton`), bean definition overriding or extra sources could replace one.
  - `DBSSLROOTCERT` (`DB_SSL_ROOT_CERT`, `db.ssl.root.cert`, `db-ssl-root-cert`), empty or set: the bundle the TLS startup assertions (`DatabaseTlsGuard`, `KafkaTlsGuard`; on by default) verify against; the chart sets it from the mounted RDS CA bundle on every container, the migration Job included, and the service refuses to start without it. A values key can neither blank it nor point the assertions at another file.
  - `FINTECHBANKXTLS*` (`FINTECHBANKXTLSENFORCE`): the vendored guard refuses `fintechbankx.tls.*` in every spelling it canonicalises (`FINTECHBANKX_TLS_ENFORCE`, `fintechbankx.tls.enforce`, dashes, indexes); this rule covers the one it keeps apart. `fintechbankx.tls.enforce=false` is the test switch of the startup assertions; with the `local` profile it is the only way off, and neither is for a cluster.
  - `SPRINGPROFILES*` (`SPRING_PROFILES_ACTIVE`, `_INCLUDE`, `_DEFAULT`, `_GROUP_*`, indexed or not), whatever the value: the chart renders the only profile, `SPRING_PROFILES_ACTIVE`, from the Helm value `kafka.runtime` (`msk` -> `kafka-msk`, the default; `strimzi` -> `kafka-strimzi`). A profile from values could activate an `application-<profile>.yml` in the image, such as `local` (a developer machine).
  - Every config value is one string (a list or map would render as its Go form) and contains no `$(`: the migration Job renders `config.DB_URL` and `config.DB_USERNAME` into container env values, where Kubernetes would expand `$(DB_MIGRATION_USERNAME)` from the `db-migration` Secret after the check. The templates quote every key and value they interpolate and render numbers with `int`, so a newline in a value cannot add a field.
  - Kafka settings in config: `KAFKA_SECURITY_PROTOCOL` (and any `*security.protocol` key) must be `SASL_SSL` with `kafka.runtime: msk` and `SSL` with `strimzi`; any `*endpoint.identification.algorithm` key must be `https`.
  - `config.*` is the only route from values into the pods' environment: the chart has no `extraEnv`, `envFrom` or JVM options value and no ExternalSecret `dataFrom`, so the guard's adapter passes none, and the deployability job fails if `values.yaml` gains an env list before the adapter maps it.
- Kafka TLS guard: on by default, relay on or off (`OUTBOX_RELAY_ENABLED` gates publishing only, so a wrong producer is caught at the first deploy, not at step 5), the service's `KafkaTlsGuard` refuses to start unless the producer's effective `security.protocol` is `SASL_SSL` (Amazon MSK with IAM, the `kafka-msk` profile) or `SSL` (in-cluster Strimzi mutual TLS with the KafkaUser certificate, the `kafka-strimzi` profile with `kafkaClientTls.enabled`), read the way Spring Boot builds the producer properties (bound from the environment in a `BeanFactoryPostProcessor`, like `DatabaseTlsGuard`, so it runs before any Kafka bean and `spring.main.lazy-initialization=true` cannot skip it), so a producer-level override cannot downgrade it to `PLAINTEXT` or `SASL_PLAINTEXT`. Both profiles therefore start in this chart, where the RDS CA bundle is always mounted, and the chart's defaults (`KAFKA_SECURITY_PROTOCOL: SASL_SSL`, `kafka.runtime: msk`, which renders `SPRING_PROFILES_ACTIVE: kafka-msk`) already satisfy the guard with the relay off (the deployability job asserts this); for an in-cluster Strimzi set `--set kafka.runtime=strimzi --set kafkaClientTls.enabled=true --set config.KAFKA_SECURITY_PROTOCOL=SSL` (the chart refuses `strimzi` without `kafkaClientTls`, and either runtime with the other's protocol); the guard is off only with the `local` profile or `fintechbankx.tls.enforce=false` (tests), both refused as config keys by the chart. The migration Job does not load it (no Kafka in the migrate-only context).
- Secrets Manager secrets (`db-app`, `db-migration`) use the secrets-only KMS key `alias/<name>-secrets`, tagged `fintechbankx.io/secrets=true`. Aurora storage, snapshots and Performance Insights use the separate, untagged `alias/<name>-db` key, which the External Secrets role cannot decrypt (ADR-023). On an environment applied before this split, Terraform points the two secrets at the new key and Secrets Manager re-encrypts their current versions; an External Secrets sync error during the apply is possible and clears on the next refresh.
- Computed screening facts (decision 0003, section 3a): the chart requires `config.CUSTOMER_SERVICE_URL` (the customer context's in-mesh URL) and `config.OIDC_TOKEN_URI` (the realm's token endpoint, https only) and renders `SVC_CLIENT_ID`, `COMPLIANCE_V1_CALLER_FACTS_ENABLED` ("false" by default) and `COMPLIANCE_LIST_MAX_AGE_SANCTIONS`/`_PEP` from `config.*` with their values checked (a dropped or empty key, a flag other than "true"/"false", a non-ISO-8601 age, an http token endpoint or a property spelling of these settings fails the render). The client credential `SVC_CLIENT_SECRET` is read from the existing Kubernetes Secret `identity.clientSecret.secretName`/`key` (default `compliance-evidence-service-svc-client`/`client-secret`) through `valueFrom.secretKeyRef`, not optional: platform creates the Keycloak client `svc-cmp-evidence` (client-credentials grant, audience `svc-cus-profile-kyc`), stores the credential in Secrets Manager under `<env>/compliance-evidence-service/svc-client` and syncs it into the namespace before this release; the chart ships no ExternalSecret or SecretStore for it (platform rule), and the migration Job never mounts it. Without the Secret the pods do not start.
- An existing Helm release from before `app.kubernetes.io/component: service` was added to the selectors cannot be upgraded, because a Deployment's selector is immutable. Delete the Deployment (`kubectl -n compliance delete deployment <release>`), then run `helm upgrade`. Nothing is installed today.

| Step | Action | Owner | Rollback trigger | Rollback |
|---|---|---|---|---|
| 1 | Deploy the service with the chart default `OUTBOX_RELAY_ENABLED: "false"`; screenings are stored and their events wait in `outbox_event`. Run the backfill; reconcile | compliance squad (deploy), DBA (bootstrap, backfill) | reconciliation fails, or the service is not ready within 10 min of the deploy | drop `sc_cmp_evidence`, nothing else changed  |
| 2 | Preconditions (section 3a, "Screening lists"): the real sanctions lists (UN, and OFAC or the UAE local list as the business names them) and the PEP register are loaded and fresh in the environment; the daily load job owned by compliance operations runs; the Keycloak client `svc-cmp-evidence` exists with the client-credentials grant and the `svc-cus-profile-kyc` audience, and `SVC_CLIENT_SECRET` is in the pods; the customer context serves `GET /api/v1/customers/{id}/screening-profile` and lists `svc-cmp-evidence` on `SERVICE_CALLERS_SCREENING`; `COMPLIANCE_V1_CALLER_FACTS_ENABLED` is off (the default). Payments call `POST /api/v2/compliance/screen` with the payment id as `transactionId`, the customer id, amount and currency and a client-credentials token (`SERVICE` role), behind a flag; they send no sanctions, PEP or KYC flag | payments squad, with the compliance squad; compliance operations (lists); platform (Keycloak client) | screening 5xx rate above 1 % over 5 min (a sustained 503 `SCREENING_SOURCE_UNAVAILABLE` means a list went stale or the customer context is down), p99 above 2 s, or any 400 caused by a caller-stated flag | flag off; payments keep their local checks. While v1 is still shipped, `COMPLIANCE_V1_CALLER_FACTS_ENABLED=true` restores the caller-attested v1 as a last resort (decision 0003, "Reversibility")  |
| 3 | **Only when** report generation and the review/submission workflow run in `svc-cmp-evidence` (not yet built) **and** the report-file plan above is done: monolith stops writing `compliance_reports`; run the backfill a last time. Until then the monolith stays the writer and the backfill keeps re-running as a mirror | compliance squad, with the monolith owner | last backfill does not reconcile, or a report written in the monolith after the stop | monolith table is still intact  |
| 4 | After the next regulatory reporting cycle: drop the monolith table | monolith owner, compliance squad sign-off | any open regulator query on the period, or a reconciliation gap found afterwards | restore from snapshot  |
| 5 | Preconditions: the mesh contract lists `msk` for `compliance-evidence-service` (allow-egress-msk generated for namespace `compliance`); topic `evt.cmp.compliance.v1` exists on MSK (no DLQ: this service consumes nothing; the per-event topic `evt.cmp.compliance.screened.v1` is retired and must not be created, ADR-019 section 8); `msk_cluster_arn` is set. Then turn the relay on with `--set-string config.OUTBOX_RELAY_ENABLED=true` (or in the environment's values file); watch `outbox_pending_events` drain and `outbox_parked_rows` stay 0; consumers subscribe to `evt.cmp.compliance.v1`, filter on the `eventType` header and skip any other type without failing or dead-lettering it | compliance squad, platform (mesh, MSK) | `outbox_parked_events_total` increases (OutboxEventsParked), or `outbox_oldest_pending_age_seconds` above the alert threshold (900 s for 5 min, see "Parked outbox events") | set it back to `"false"`; events stay in the outbox and are sent in order once it is back on  |

Owners and triggers are Proposed; the owning squads confirm them before step 1.

### 3a. Screening lists (sanctions, PEP)

Compliance computes the sanctions and PEP facts by matching the customer's normalised full name against two tables it owns, `sc_cmp_evidence.sanctions_list_entry` and `sc_cmp_evidence.pep_register_entry` (V13; [decision 0003](../architecture/decisions/0003-compliance-computes-sanctions-pep-and-kyc.md)). The service only reads them (the runtime role gets `SELECT`); compliance operations load them.

**Loader.** `scripts/ops/load-screening-list.sh <conninfo> <sanctions|pep> <names.csv> <list-source> <list-version>` replaces one source of one list (for example `UN`, `OFAC-SDN`, the UAE local list under the name the business uses) in a single transaction: the previous rows of that source are deleted and the new ones inserted together, other sources are untouched, and a failure anywhere leaves the previous load in place. The CSV is UTF-8 with one column and the header `display_name`; the loader normalises every name with `scripts/ops/normalize-screening-name.py`, the same rule the service matches with (NFD, combining marks removed, upper case, letters and digits only, single spaces; the Java and Python rules are kept in step by the shared fixtures `scripts/ops/test/screening-name-fixtures.csv`, checked by `NormalizedNameTest` and by the CI rehearsal `scripts/ops/test/rehearse-screening-list-load.sh` in the deployability workflow). It refuses a credential in the conninfo (use `PGPASSWORD`, a pgpass file or a service file), an empty or header-only file, a wrong header, an unknown list, a malformed source or version, and any name that normalises to nothing. Run it as the schema owner (`compliance_evidence_owner`, from the migration host) or as a loader role the DBA creates with `GRANT SELECT, INSERT, DELETE ON sc_cmp_evidence.sanctions_list_entry, sc_cmp_evidence.pep_register_entry` and `USAGE` on the schema; never as the runtime role, which cannot write them. `list-version` is the publisher's version or date and is stored on every screening the load was matched against (`list_versions`), so an auditor can tell which list decided a case.

**Freshness.** A screening is refused with 503 `SCREENING_SOURCE_UNAVAILABLE` while a list is empty or any of its sources was loaded longer ago than `COMPLIANCE_LIST_MAX_AGE_SANCTIONS` (default `P1D`) or `COMPLIANCE_LIST_MAX_AGE_PEP` (default `P35D`); nothing is recorded and payments retry. A list that is not reloaded in time therefore stops screening, by design: the load job is part of the service's availability. Check the loads with `select list_source, list_version, max(loaded_at), count(*) from sc_cmp_evidence.sanctions_list_entry group by 1, 2` (and the PEP table).

**Daily load job (owned by compliance operations; Proposed).** One run per day per sanctions source and one per PEP register release, from the publishers' files, before the sanctions maximum age lapses; alert on a load that did not run in 20 hours and on any `SCREENING_SOURCE_UNAVAILABLE` rate above zero for 15 minutes. The job's host needs `psql`, `python3`, the loader scripts from this repository at the deployed tag, and the loader role's credential from Secrets Manager. The format of the publishers' files and the conversion to the one-column CSV are the operations team's; this repository ships only the loader and its rehearsal.

**Cut-over preconditions (step 2 above):**

- UN, and OFAC or the UAE local sanctions list (as the business names them), loaded with their real contents and `loaded_at` within `COMPLIANCE_LIST_MAX_AGE_SANCTIONS`.
- The PEP register loaded with its real contents and `loaded_at` within `COMPLIANCE_LIST_MAX_AGE_PEP`.
- The daily load job scheduled, owned by compliance operations, with its alerts.
- `COMPLIANCE_V1_CALLER_FACTS_ENABLED` off (the default) in every environment; payments on `POST /api/v2/compliance/screen`.
- Keycloak client `svc-cmp-evidence` (client-credentials grant, audience `svc-cus-profile-kyc`), its credential in Secrets Manager `<env>/compliance-evidence-service/svc-client` and synced to the Secret `identity.clientSecret.secretName` (platform), `config.OIDC_TOKEN_URI` and `config.CUSTOMER_SERVICE_URL` set in the environment's values; the customer context's patch (`GET /api/v1/customers/{id}/screening-profile`, `svc-cmp-evidence` on `SERVICE_CALLERS_SCREENING`) is deployed first.

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
- [x] `sanctionsHit`, `pep` and `kycVerified` have a real source: compliance computes them on `POST /api/v2/compliance/screen` from its own lists and the customer context (decision 0003; `ComplianceServiceIT`); v1 deprecated behind `COMPLIANCE_V1_CALLER_FACTS_ENABLED`, 410 by default
- [ ] Cut-over precondition: real sanctions lists (UN, and OFAC or the UAE local list) and the PEP register loaded and fresh in the environment; daily load job owned by compliance operations (section 3a)
- [x] Chart renders the screening settings (`CUSTOMER_SERVICE_URL`, `OIDC_TOKEN_URI` required; `SVC_CLIENT_ID`, `COMPLIANCE_V1_CALLER_FACTS_ENABLED`, list ages checked) and mounts `SVC_CLIENT_SECRET` from an existing Secret only (`scripts/ci/chart-render-probes.sh`); Proposed: not yet rendered on a cluster
- [ ] Platform: Keycloak client `svc-cmp-evidence` with the `svc-cus-profile-kyc` audience; credential in Secrets Manager `<env>/compliance-evidence-service/svc-client`, synced to the namespace Secret; environment values for `config.OIDC_TOKEN_URI` and `config.CUSTOMER_SERVICE_URL`
- [ ] Customer context serves the screening profile and lists `svc-cmp-evidence` on `SERVICE_CALLERS_SCREENING` (deploy before this release is used for v2)
- [ ] DBA: loader role (or the owner runs the loader) with `SELECT, INSERT, DELETE` on the two list tables
- [ ] Payments on v2: no sanctions, PEP or KYC flag in the request
- [x] Screening events written through a transactional outbox in the screening's transaction; one event per new screening, none on retries or when a concurrent duplicate loses (`ComplianceServiceIT`); screening inputs not published
- [x] Outbox failures per ADR-021 decision 4: payload errors park the row (skipped, counted once by `outbox_parked_events_total`, shown by `outbox_parked_rows`, replayed by hand); any other failure stops the batch without marking the row, backs off and alerts on `outbox_oldest_pending_age_seconds`, never parks; one sender at a time (`OutboxRelayTest`, `ComplianceServiceIT`)
- [ ] AsyncAPI catalog entry (proposed in asyncapi-catalog PR #11, not merged) matches `api/asyncapi/svc-cmp-evidence.yaml` (provider copy changes `screeningId` from `format: uuid` to the `CMP-<uuid>` pattern)
- [ ] Topic `evt.cmp.compliance.v1` created on the platform cluster (producer only, so no DLQ; not the retired per-event `evt.cmp.compliance.screened.v1`); IRSA `msk_cluster_arn` set
- [ ] Flyway V11 applied: unsent outbox rows written before it name `evt.cmp.compliance.v1` (`select topic, count(*) from sc_cmp_evidence.outbox_event where published_at is null group by topic` shows only that topic)
- [ ] Mesh contract lists `msk` for `compliance-evidence-service` (allow-egress-msk generated for namespace `compliance`); until then the chart keeps the relay off
- [ ] Report generation and the review/submission workflow moved here (precondition for step 3; today only the history is mirrored)
- [ ] Plan for report files at `report_file_path` (not migrated)
- [x] Runtime role separation: grants and IT in place (V7 and V12 grants, Flyway `DB_MIGRATION_*` credentials, Helm migration secret, Terraform secret; in the IT the runtime role cannot `UPDATE`/`DELETE`/`TRUNCATE` evidence or disable the trigger, `ComplianceServiceIT`); effective in an environment only after the DBA bootstrap and the migration Job have run there
- [ ] DBA bootstrap of `compliance_evidence_owner` and `compliance_evidence_app` per environment, secrets filled
- [x] Flyway moved out of the pods into a Helm `pre-install`/`pre-upgrade` migration Job; the service pods mount only the runtime credential and validate at startup (decision 0002; `DatabaseMigrationIT`, `scripts/ci/check-migration-job.py` in `deploy/helm`). Proposed: not yet run on a cluster
- [ ] Production backfill and reconciliation report attached here
