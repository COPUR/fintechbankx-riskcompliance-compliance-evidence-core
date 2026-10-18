# 0003. Compliance computes the sanctions, PEP and KYC facts; the caller sends ids and the amount

- Status: Proposed
- Date: 2026-10-18
- Owner: Risk and Compliance Decisioning Squad
- Scope: `svc-cmp-evidence`, `POST /api/v2/compliance/screen`, `POST /api/v1/compliance/screen` (deprecated), schema `sc_cmp_evidence`
- Supersedes the "until compliance resolves them itself" part of decision 0001

## Context

Decision 0001 recorded that the screening facts (sanctions hit, PEP, KYC)
were stated by the caller and not verified by compliance, and listed
resolving them in compliance as a follow-up. A decision made on facts the
caller states proves only what the caller said; a caller can also only state
what it knows, and payments knows neither the sanctions lists nor the KYC
status. The screening-facts plan (contract approach and compliance part)
moves each fact to its owner: KYC to the customer context, sanctions and PEP
to compliance.

## Decision

1. **A second operation, `POST /api/v2/compliance/screen`, takes
   `transactionId`, `customerId`, `amount` and `currency` only.** A body that
   carries `sanctionsHit`, `kycVerified` or `pep` is a 400: the caller is on
   the retired contract and must not think its flag was used.
2. **Compliance resolves the facts at screening time.**
   - Name and KYC status: `GET /api/v1/customers/{id}/screening-profile` of
     the customer context, which returns exactly `customerId`, `firstName`,
     `lastName` and `kycStatus` (after the DevSecOps review compliance must
     not read the full profile), through the out-port `CustomerProfilePort`
     and the HTTP adapter `CustomerProfileHttpAdapter`. `kycVerified` is true exactly when the
     customer context says `VERIFIED`; `PENDING` and `REJECTED` are not
     verified; any other value, or no `kycStatus`, is contract drift and a
     source outage, never a fact.
   - Sanctions and PEP: the customer's full name, normalised (NFD with
     combining marks removed, upper case, letters and digits only, single
     spaces: `NormalizedName`), is matched exactly against the
     compliance-owned tables `sanctions_list_entry` and `pep_register_entry`
     (V13) through `NameScreeningPort` (`JdbcNameScreeningAdapter`). Exact
     matching on the shared normalisation is the minimum the port allows; a
     fuzzy or vendor screening adapter replaces the class, not the port.
   - This repository had no client-credentials pattern to reuse, so the
     service obtains its own token (`ClientCredentialsTokenProvider`:
     `SVC_CLIENT_ID`, `SVC_CLIENT_SECRET`, `OIDC_TOKEN_URI`; no scope
     parameter) and caches it until shortly before it expires. The customer
     context requires `aud` `svc-cus-profile-kyc` on that token and lists
     `svc-cmp-evidence` on `SERVICE_CALLERS_SCREENING`; both are the Keycloak
     client's and the customer squad's configuration, not this service's code.
   - The credential follows the platform's client-secret delivery: the single
     Secrets Manager entry `<env>/compliance-evidence-service/oidc-client`
     (a JSON object with `client_id`, `client_secret`, `issuer_uri`,
     `token_uri`, published by the platform identity repo), read by the
     chart's own third ExternalSecret against the ClusterSecretStore
     `aws-secrets-manager` (`externalSecret.oidcClientSecretName`): property
     `client_secret` becomes key `SVC_CLIENT_SECRET` of the Secret
     `<service account>-oidc-client`, which only the app pods load with
     `envFrom`; the migration Job never does, and no config key may carry it.
     `OIDC_TOKEN_URI` stays a checked config value (https only) rather than
     being read from the entry's `token_uri`, so the chart can verify it; the
     two must name the same realm endpoint.
3. **A fact that cannot be resolved stops the screening.** The customer
   context unreachable, refusing (401/403), failing (5xx) or answering
   something the adapter cannot read, a list empty, a list missing one of its
   required sources (`COMPLIANCE_SANCTIONS_REQUIRED_SOURCES`, for example
   `UN,OFAC-SDN,UAE-LOCAL`; `COMPLIANCE_PEP_REQUIRED_SOURCES`, for example
   `UAE-PEP`; both required by the chart), or any list source loaded longer
   ago than its maximum age (`COMPLIANCE_LIST_MAX_AGE_SANCTIONS`, default
   P1D; `COMPLIANCE_LIST_MAX_AGE_PEP`, default P35D; `ListFreshnessPolicy`)
   is a 503 `SCREENING_SOURCE_UNAVAILABLE`, for a hit as much as for a no-hit;
   nothing is recorded and the caller retries. The screening never passes, and
   never fails, by default: a stale or partial list can no more FAIL a
   transaction than PASS one, and a name that is only on the missing list
   must not pass.
4. **A customer the customer context does not know is a 422
   `CUSTOMER_NOT_FOUND`, not a 503.** The owner gave a definite answer; a 503
   would make payments retry a wrong id until a timeout, and a 404 of our own
   would be read as "no screening" by clients of the GET. Nothing is recorded.
   Only the customer context's own answer counts: a 404 with content type
   `application/json` whose `ErrorResponse` (`code`, `message`,
   `interactionId`, `timestamp`) carries `code` `CUSTOMER_NOT_FOUND`. Any
   other 404 (bare, another code or shape, `application/problem+json`) is a
   route, ingress or mesh answer, not a statement about the customer, and is
   a 503.
   Reversible: the mapping is one handler in `ApiExceptionHandler`.
5. **Evidence.** `attestation_source` gains `COMPUTED`; `attested_by` records
   who requested the screening (the token `azp` of a service, the `sub` of a
   staff member), so the evidence still names the caller. The row stores the
   facts as before and, new, `list_versions`: every source load of both lists
   at the time of the match (`list`, `source`, `version`, `loadedAt`). A
   `COMPUTED` row must name at least one load (constraint
   `ck_compliance_screening_computed_lists`). The response carries
   `listVersions`; the event does not change (ids, decision, checked time; no
   reason codes, facts, amount or list data).
6. **Replay.** The same `transactionId` with the same customer, amount by
   value, currency and requester returns the stored result without consulting
   any source again: the facts of record are those of the first screening.
   Any other combination is a 409 `TRANSACTION_ALREADY_SCREENED`; a v1 result
   is never handed out for a v2 request, nor the other way round.
7. **v1 stays, deprecated, behind a flag.** `POST /api/v1/compliance/screen`
   is served while `COMPLIANCE_V1_CALLER_FACTS_ENABLED=true`, the default in
   the application and the chart until payments has moved to v2 (this release
   must not break the caller it is deployed under); the runbook's cut-over
   step then sets it to `"false"`, after which it answers 410 `ENDPOINT_GONE`
   and names v2. The GET is unaffected.
8. **The lists are loaded by compliance operations**, not by the service:
   `scripts/ops/load-screening-list.sh` replaces one source of one list in a
   transaction and refuses credentials on the command line (keyword, URI
   user-info and URI query forms), empty files and names that normalise to
   nothing, and refuses a reload that would shrink a source below 90% of its
   current rows (a truncated file) unless `--allow-shrink` is passed, which is
   logged. The runtime role has SELECT only on both tables. The loader normalises names with
   `scripts/ops/normalize-screening-name.py`; the Java and Python rules are
   kept in step by the shared fixtures `scripts/ops/test/screening-name-fixtures.csv`,
   checked by `NormalizedNameTest` and by the CI rehearsal
   `scripts/ops/test/rehearse-screening-list-load.sh`.

## To confirm before cut-over (Group Compliance)

- The v1 sunset date (`x-sunset` 2027-06-30 in the OpenAPI contract is Proposed).
- Exact name matching on the shared normalisation for the first step, or a
  vendor or fuzzy screening adapter behind `NameScreeningPort` before cut-over
  (see the known limitation under Consequences).
- The list maximum ages (sanctions P1D, PEP P35D) and the required sources
  (`UN,OFAC-SDN,UAE-LOCAL`; `UAE-PEP`).

## Consequences

- A `COMPUTED` decision proves what compliance looked up, against which list
  loads, at that moment.
- Compliance now depends on two sources at request time; their outages become
  503s for payments, which is the intended failure mode (fail closed).
- The lists are only as good as their loads: the cut-over needs the real
  sanctions lists (UN, and OFAC or the UAE local list as the business names
  them) and the PEP register loaded and fresh, and a daily load job owned by
  compliance operations (runbook, "Screening lists").
- Known limitation, Proposed, to confirm by Group Compliance before cut-over;
  this is not a compliance claim. The match is an exact comparison of the
  normalised first and last name in FIRST LAST order only: a list entry
  written "LAST, FIRST" misses, a middle name on one side only misses, and
  aliases and transliterations other than the one on the list miss. Whether
  exact matching is acceptable for the first step, or a vendor or fuzzy
  adapter behind `NameScreeningPort` must come before cut-over, is Group
  Compliance's call; `list_versions` lets a later re-screen be compared.
- `attested_by` keeps the caller for `COMPUTED` rows, so the replay rule
  (same requester) and the 409 for another caller stay as in decision 0001.

## Follow-ups

- Customer squad: `GET /api/v1/customers/{id}/screening-profile` and
  `svc-cmp-evidence` on `SERVICE_CALLERS_SCREENING` (the customer patch
  follows this one and must be deployed first; Proposed until merged).
- Platform: Keycloak client `svc-cmp-evidence` (realm role `service`,
  client-credentials grant, audience `svc-cus-profile-kyc`, no scope
  parameter) and its Secrets Manager entry
  `<env>/compliance-evidence-service/oidc-client` published with
  `publish-oidc-client-secrets.sh`; the mesh allowing compliance only
  `GET .../screening-profile`. This chart reads the entry with its own
  ExternalSecret (`externalSecret.oidcClientSecretName`, required and
  separate from the database entries) and needs the environment values for
  `config.CUSTOMER_SERVICE_URL` and `config.OIDC_TOKEN_URI`. The chart
  renders and checks `SVC_CLIENT_ID`, `COMPLIANCE_V1_CALLER_FACTS_ENABLED`,
  `COMPLIANCE_LIST_MAX_AGE_*` and `COMPLIANCE_*_REQUIRED_SOURCES` with
  defaults. Rotation: runbook, "Rotating the client credential".
- DBA: a loader role with SELECT, INSERT, DELETE on the two list tables, or
  the schema owner runs the loader.
- Payments: move to v2 and drop the three flags from the request.

## Reversibility

Reversible while v1 is still shipped: `COMPLIANCE_V1_CALLER_FACTS_ENABLED`
is `true` until the cut-over step turns it off, and setting it back to `true`
lets payments fall back to v1. The migration V13 adds tables, a column with a
default and a wider check; nothing is dropped. Rows written as `COMPUTED`
stay valid evidence either way.
