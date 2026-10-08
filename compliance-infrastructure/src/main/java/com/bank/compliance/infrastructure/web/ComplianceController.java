package com.bank.compliance.infrastructure.web;

import com.bank.compliance.application.dto.ComplianceScreeningRequest;
import com.bank.compliance.application.dto.ComplianceScreeningResponse;
import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    private final ComplianceScreeningUseCase service;

    public ComplianceController(ComplianceScreeningUseCase service) {
        this.service = service;
    }

    @PostMapping("/screen")
    @PreAuthorize("hasAnyRole('SERVICE', 'COMPLIANCE_OFFICER', 'ADMIN')")
    public ResponseEntity<ComplianceScreeningResponse> screen(@Valid @RequestBody ComplianceScreeningRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ComplianceScreeningResponse.from(service.screen(request.toCommand())));
    }

    @GetMapping("/screenings/{transactionId}")
    @PreAuthorize("hasAnyRole('SERVICE', 'COMPLIANCE_OFFICER', 'AUDITOR', 'ADMIN')")
    public ResponseEntity<?> find(@PathVariable String transactionId) {
        return service.findByTransactionId(transactionId)
                .<ResponseEntity<?>>map(result -> ResponseEntity.ok(ComplianceScreeningResponse.from(result)))
                .orElseGet(() -> ApiExceptionHandler.error(HttpStatus.NOT_FOUND, "SCREENING_NOT_FOUND",
                        "No screening result for this transaction"));
    }
}
