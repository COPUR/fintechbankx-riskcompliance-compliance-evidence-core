package com.bank.compliance.infrastructure.web;

import com.bank.compliance.infrastructure.web.dto.ComplianceScreeningRequest;
import com.bank.compliance.infrastructure.web.dto.ComplianceScreeningResponse;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Compliance screening of transactions (v1) and reading of results. Called by
 * payment and lending services with a SERVICE-role client-credentials token;
 * results are read by compliance staff as evidence.
 *
 * POST /api/v1/compliance/screen takes the sanctions, PEP and KYC facts from
 * the caller. It is deprecated: compliance computes those facts on
 * POST /api/v2/compliance/screen (ComplianceScreeningV2Controller). The v1
 * screening is served only while COMPLIANCE_V1_CALLER_FACTS_ENABLED is true;
 * otherwise it answers 410 ENDPOINT_GONE and names v2. Reading results is
 * unaffected by the flag.
 *
 * Depends on the in-port, which Spring provides only as the transactional
 * use case (see ComplianceConfiguration), so a screening and its outbox event
 * are always written in one transaction.
 */
@RestController
@RequestMapping("/api/v1/compliance")
public class ComplianceController {
    /** SERVICE role from a client on SERVICE_CALLERS (token azp); see ServiceCallerPolicy. */
    static final String LISTED_SERVICE = "(hasRole('SERVICE') and @serviceCallers.allowed(authentication))";
    static final String V1_GONE = "POST /api/v1/compliance/screen is retired: compliance computes the screening facts on "
            + "POST /api/v2/compliance/screen. The v1 screening is served only while COMPLIANCE_V1_CALLER_FACTS_ENABLED=true.";

    private final ComplianceScreeningUseCase service;
    private final CallerAttestation callerAttestation;
    private final boolean v1CallerFactsEnabled;

    public ComplianceController(ComplianceScreeningUseCase service, CallerAttestation callerAttestation,
                                @Value("${compliance.v1-caller-facts.enabled}") boolean v1CallerFactsEnabled) {
        this.service = service;
        this.callerAttestation = callerAttestation;
        this.v1CallerFactsEnabled = v1CallerFactsEnabled;
    }

    /** @deprecated caller-attested facts; use POST /api/v2/compliance/screen. */
    @Deprecated
    @PostMapping("/screen")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN') or " + LISTED_SERVICE)
    public ResponseEntity<?> screen(@Valid @RequestBody ComplianceScreeningRequest request,
                                    Authentication authentication) {
        if (!v1CallerFactsEnabled) {
            return ApiExceptionHandler.error(HttpStatus.GONE, "ENDPOINT_GONE", V1_GONE);
        }
        // Who attests the facts comes from the verified token, never from the request body.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ComplianceScreeningResponse.from(service.screen(request.toCommand(callerAttestation.of(authentication)))));
    }

    @GetMapping("/screenings/{transactionId}")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'AUDITOR', 'ADMIN') or " + LISTED_SERVICE)
    public ResponseEntity<?> find(@PathVariable String transactionId) {
        return service.findByTransactionId(transactionId)
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok(ComplianceScreeningResponse.from(result)))
                .orElseGet(() -> ApiExceptionHandler.error(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND",
                        "No screening result for this transaction"));
    }
}
