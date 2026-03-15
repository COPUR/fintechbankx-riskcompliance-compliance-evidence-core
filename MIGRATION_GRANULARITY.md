# Migration Granularity Notes

- Repository: `fintechbankx-compliance-evidence-service`
- Source monorepo: `enterprise-loan-management-system`
- Sync date: `2026-03-15`
- Sync branch: `chore/granular-source-sync-20260313`

## Applied Rules

- dir: `compliance-context` -> `.`
- file: `api/openapi/compliance-context.yaml` -> `api/openapi/compliance-context.yaml`
- file: `docs/architecture/open-finance/capabilities/test-suites/audit-liability-compliance-test-suite.md` -> `docs/test-suites/audit-liability-compliance-test-suite.md`

## Notes

- This is an extraction seed for bounded-context split migration.
- Follow-up refactoring may be needed to remove residual cross-context coupling.
- Build artifacts and local machine files are excluded by policy.

