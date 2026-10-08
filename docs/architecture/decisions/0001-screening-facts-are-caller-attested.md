# 0001. Screening facts are caller-attested until compliance resolves them itself

- Status: Proposed
- Date: 2026-10-08
- Owner: Risk and Compliance Decisioning Squad
- Scope: `svc-cmp-evidence`, `POST /api/v1/compliance/screen`

## Context

The screening decision (PASS / REVIEW / FAIL) is made from four inputs: whether
the counterparty hit a sanctions list, whether the customer's KYC is verified,
whether the customer is a politically exposed person (PEP), and the amount.
Today the calling service (payments, lending) sends all four in the request.
`svc-cmp-evidence` does not look any of them up: it has no sanctions or PEP
list integration, and it does not read KYC status from `svc-cus-profile-kyc`.

So a stored decision proves only what the rules concluded from what the caller
stated. Evidence that hides this would overstate what compliance checked.

## Decision

1. Every screening records where its facts came from: `attestation_source`
   on `sc_cmp_evidence.compliance_screening`, `AttestationSource` in the domain,
   and the additive `attestation` field on the API response. The only value
   today is `CALLER_ATTESTED`.
2. The facts themselves (amount, currency, the three flags) and the rule set
   version are stored with the decision. A replay of the same `transactionId`
   must state the same customer and the same facts to get the stored result;
   anything else is `409 TRANSACTION_ALREADY_SCREENED` and the evidence is kept.
3. The facts are not published on `evt.cmp.compliance.screened.v1`; entitled
   readers get them through the compliance API.

## Consequences

- Auditors can tell a caller-attested decision from one compliance verified.
- A caller cannot get a PASS for one set of facts and reuse it for another.
- Compliance still depends on callers telling the truth. This is the main risk
  this record accepts, until the follow-ups below land.
- When compliance starts resolving facts itself, new `AttestationSource`
  values are added (for example `KYC_RESOLVED_FROM_CUSTOMER_SERVICE`,
  `SANCTIONS_LIST_LOOKUP`). Consumers must accept unknown values (documented
  in the OpenAPI description).

## Follow-ups

- Resolve KYC status from `svc-cus-profile-kyc` (its published API or
  `evt.cus.customer.*` events) instead of the caller's `kycVerified`.
- Run sanctions and PEP lookups in compliance (list provider to be chosen by
  the squad and Group Compliance).
- Decide whether the PEP high-value threshold (10000) is per currency; today
  it ignores the currency.
- Decide whether a replay with different facts should be refused (current
  default) or screened as new evidence under a new id.

## Reversibility

Reversible: the column, field and enum can gain values without breaking
callers. Removing them would discard evidence, so that direction needs owner
approval.
