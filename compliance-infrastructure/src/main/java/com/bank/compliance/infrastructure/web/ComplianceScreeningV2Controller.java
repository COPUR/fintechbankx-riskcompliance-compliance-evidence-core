package com.bank.compliance.infrastructure.web;

import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.infrastructure.web.dto.ComplianceScreeningResponse;
import com.bank.compliance.infrastructure.web.dto.ComputedScreeningRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Compliance screening with facts computed by compliance (v2). The caller
 * sends the transaction and customer ids, the amount and the currency;
 * compliance reads the customer's name and KYC status from the customer
 * context and matches the normalised name against its own sanctions and PEP
 * lists (docs/architecture/decisions/0003). Results are read through the v1
 * GET, which is unchanged.
 *
 * Depends on the in-port, which Spring provides only as the transactional
 * use case (see ComplianceConfiguration).
 */
@RestController
@RequestMapping("/api/v2/compliance")
public class ComplianceScreeningV2Controller {

    private final ComplianceScreeningUseCase service;
    private final CallerAttestation callerAttestation;

    public ComplianceScreeningV2Controller(ComplianceScreeningUseCase service, CallerAttestation callerAttestation) {
        this.service = service;
        this.callerAttestation = callerAttestation;
    }

    @PostMapping("/screen")
    @PreAuthorize("hasAnyRole('COMPLIANCE_OFFICER', 'ADMIN') or " + ComplianceController.LISTED_SERVICE)
    public ResponseEntity<ComplianceScreeningResponse> screen(@Valid @RequestBody ComputedScreeningRequest request,
                                                              Authentication authentication) {
        // Who requested the screening comes from the verified token, never from the request body.
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ComplianceScreeningResponse.from(
                        service.screenWithComputedFacts(request.toCommand(callerAttestation.requesterOf(authentication)))));
    }
}
