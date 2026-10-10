package com.bank.compliance.infrastructure.web;

import com.bank.compliance.infrastructure.web.dto.ComplianceScreeningRequest;
import com.bank.compliance.infrastructure.web.dto.ComplianceScreeningResponse;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

/**
 * Compliance screening of transactions. Called by payment and lending
 * services with a SERVICE-role client-credentials token; results are read by
 * compliance staff as evidence.
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
    private final ComplianceScreeningUseCase service;
    private final CallerAttestation callerAttestation;

    public ComplianceController(ComplianceScreeningUseCase service, CallerAttestation callerAttestation) {
        this.service = service;
        this.callerAttestation = callerAttestation;
    }

    @PostMapping("/screen")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN') or " + LISTED_SERVICE)
    public ResponseEntity<ComplianceScreeningResponse> screen(@Valid @RequestBody ComplianceScreeningRequest request,
                                                              Authentication authentication) {
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
