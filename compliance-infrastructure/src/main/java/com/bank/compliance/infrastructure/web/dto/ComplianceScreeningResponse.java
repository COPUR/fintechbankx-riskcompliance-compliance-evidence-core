package com.bank.compliance.infrastructure.web.dto;

import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ListVersion;

import java.time.Instant;
import java.util.List;

/**
 * A screening result as the API answers it: the decision and its reasons,
 * who attested or requested it and, for computed facts, the list loads the
 * facts were matched against. The facts themselves (flags, amount) are
 * evidence in the database, not part of the response.
 */
public record ComplianceScreeningResponse(
        String screeningId,
        String transactionId,
        String customerId,
        String decision,
        List<String> reasons,
        Instant checkedAt,
        String attestation,
        String attestedBy,
        List<ListVersionResponse> listVersions
) {
    public static ComplianceScreeningResponse from(ComplianceResult result) {
        return new ComplianceScreeningResponse(
                result.getId().getValue(),
                result.getTransactionId(),
                result.getCustomerId(),
                result.getDecision().name(),
                result.getReasons(),
                result.getCheckedAt(),
                result.getAttestation().name(),
                result.getAttestedBy(),
                result.getListVersions().stream().map(ListVersionResponse::from).toList()
        );
    }

    /** One source load of a screening list (SANCTIONS or PEP) the facts were matched against. */
    public record ListVersionResponse(String list, String source, String version, Instant loadedAt) {
        static ListVersionResponse from(ListVersion version) {
            return new ListVersionResponse(version.list().name(), version.source(), version.version(), version.loadedAt());
        }
    }
}
