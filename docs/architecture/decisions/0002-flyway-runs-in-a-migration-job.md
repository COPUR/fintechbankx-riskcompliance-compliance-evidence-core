# 0002. Flyway runs in a Helm-hooked migration Job; the service pods only validate

- Status: Proposed
- Date: 2026-10-10
- Owner: Risk and Compliance Decisioning Squad
- Scope: `svc-cmp-evidence`, chart `deploy/helm/compliance-evidence-service`, schema `sc_cmp_evidence`

## Context

`compliance_screening` is insert-only evidence for the runtime role: V7 grants
it only `SELECT, INSERT` and the V4 trigger refuses `UPDATE` and `DELETE`. The
table owner (the migration role) can still disable the trigger, `TRUNCATE` or
drop the table.

Until this decision Flyway ran at service startup, so every service pod mounted
the `db-migration` secret (the schema owner's credential) next to the runtime
credential. Anyone with a pod's environment (a compromised pod, `kubectl exec`,
a leaked env dump) could act as the owner, and the insert-only guarantee did
not hold in practice. A review marked this as blocking.

The platform convention (cicd-templates 335a345) already expects services to
run Flyway as a Job: its pods carry `app.kubernetes.io/name=<service account>`
(the mesh grants Aurora egress on that label) and
`app.kubernetes.io/component=db-migration`, and every app selector includes
`app.kubernetes.io/component=service`, so the Job's pods are never selected.

## Decision

1. **Flyway runs only in a Helm hook Job** (`templates/migration-job.yaml`),
   `pre-install` and `pre-upgrade`. Helm runs it before it creates or updates
   any regular resource of the release. A failed or timed-out Job fails the
   install or upgrade, so the Deployment is not rolled out on an unmigrated
   schema. There is no `pre-rollback` hook: Flyway has no down migrations, and
   an older image validates against a newer schema history (Flyway ignores
   applied migrations it does not know, `ignoreMigrationPatterns` `*:future`).
2. **The Job reuses the service image in migrate-only mode.** With the first
   argument `migrate`, `ComplianceEvidenceApplication` hands over to
   `DatabaseMigration`. That starts only the datasource, Flyway and
   `DatabaseTlsGuard` (profile `db-migrate`, `compliance.database.flyway=migrate`),
   migrates as the schema owner and exits: 0 when every migration is applied,
   1 otherwise. It starts no web server, JPA, Kafka, security or outbox relay.
3. **Only the Job sees the owner credential.** The Job gets the `db-migration`
   ExternalSecret, the same verified `DB_URL` (from values, checked by
   `compliance.validateDatabaseTls`), `DB_USERNAME`, the `rds-ca-bundle` mount
   and `DB_SSL_ROOT_CERT`. It has the app's pod and container security
   contexts (non-root, read-only root filesystem, all capabilities dropped).
   The `db-migration` ExternalSecret and the Job's own ServiceAccount (no IAM
   role, no API token) are hooks at weight -10, created before the Job, and
   deleted once every hook has succeeded. The owner credential exists in the
   namespace only while a migration runs. The Deployment mounts only the
   runtime credential.
4. **The service validates as the runtime role.** `compliance.database.flyway`
   defaults to `validate` (`FlywayStartupConfiguration`): at startup Flyway
   checks the schema history and the service refuses to start while a
   migration is pending or an applied one differs. V12 grants the runtime role
   `SELECT` only on `flyway_schema_history`, so it can read the history but not
   record, repair or remove a migration. Tests keep `migrate` as the owner
   (`PostgresTestDatabase`). `DatabaseMigrationIT` covers the Job and the
   service.
5. **`externalSecret.migrationSecretName` is required.** The chart no longer
   renders without it, so the service can no longer migrate silently as the
   runtime role.
6. CI (`deploy/helm`, `scripts/ci/check-migration-job.py`) asserts the split.
   The app pods never reference the `db-migration` secret. The Job exists with
   the hooks, limits and labels above. No Service, PDB, NetworkPolicy,
   Deployment or topology-spread selector matches its pods. kubeconform
   `-strict` and the ExternalSecret naming check pass.

## Consequences

- The schema owner's credential leaves the service pods. Tampering with
  evidence now needs the owner credential from Secrets Manager or the
  database's master user, not a pod's environment. A DBA can still change rows;
  tamper evidence (for example a hash chain) is still not built.
- Deploy order: the Job runs and must succeed before any new pod starts. Helm's
  `--timeout` must exceed the Job's `activeDeadlineSeconds` (600 s).
- The Job's pods match the mesh's `allow-egress-aurora` and `allow-egress-msk`
  policies (both select `app.kubernetes.io/name`). They have no IAM role, so
  MSK refuses them.
- The Job's pods carry `sidecar.istio.io/inject: "false"`, written after
  `podLabels` so it overrides their `"true"`. Aurora egress is a Kubernetes
  NetworkPolicy on the name label, so the Job needs no proxy; without native
  sidecars an injected proxy keeps the pod running after Flyway exits and the
  Job never completes. `check-migration-job.py` asserts the label. The Job's
  connection to Aurora is TLS verified by the driver, not by the mesh.
- A pending migration keeps new service pods from starting
  (`FlywayValidateException`). With `maxUnavailable: 0` the old pods keep
  serving, and the rollout stalls instead of serving on the wrong schema.
- Not verified on a cluster: that ESO syncs the hook ExternalSecret before
  the Job's pod gives up, and the timings. The chart is checked only by
  rendering, kubeconform and the mutation checks.

## Reversibility

Reversible. Moving Flyway back into the pods means setting
`compliance.database.flyway=migrate` in the service and mounting the secret
again. That would undo the security property, so it needs a new decision that
supersedes this one. V12's grant is additive and can be revoked by a later
migration.
