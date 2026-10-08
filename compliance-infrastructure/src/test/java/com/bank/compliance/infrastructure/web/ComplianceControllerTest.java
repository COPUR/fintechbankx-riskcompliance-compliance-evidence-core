package com.bank.compliance.infrastructure.web;

import com.bank.compliance.domain.port.in.ComplianceScreeningUseCase;
import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ScreeningAlreadyRecordedException;
import org.springframework.dao.DataIntegrityViolationException;
import com.bank.compliance.domain.ComplianceResultFixtures;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ComplianceControllerTest {

    private ComplianceScreeningUseCase service;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        service = mock(ComplianceScreeningUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new ComplianceController(service))
                .setControllerAdvice(new ApiExceptionHandler()).build();
    }

    @Test
    void shouldScreenCompliance() throws Exception {
        ComplianceResult result = ComplianceResultFixtures.result("TX-1", "C1", ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"));
        when(service.screen(any(ComplianceScreeningCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/compliance/screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-1","customerId":"C1","amount":12000,"sanctionsHit":false,"kycVerified":true,"pep":true}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transactionId").value("TX-1"))
                .andExpect(jsonPath("$.decision").value("REVIEW"));
    }

    @Test
    void shouldFindComplianceByTransactionId() throws Exception {
        ComplianceResult result = ComplianceResultFixtures.result("TX-2", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(service.findByTransactionId("TX-2")).thenReturn(Optional.of(result));
        when(service.findByTransactionId("TX-404")).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/compliance/screenings/TX-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transactionId").value("TX-2"));

        mockMvc.perform(get("/api/v1/compliance/screenings/TX-404"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCREENING_NOT_FOUND"));
    }

    @Test
    void reusedTransactionIdForAnotherCustomerIsAConflict() throws Exception {
        when(service.screen(any(ComplianceScreeningCommand.class)))
                .thenThrow(new com.bank.compliance.domain.TransactionAlreadyScreenedException("TX-3"));

        mockMvc.perform(post("/api/v1/compliance/screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-3","customerId":"C2","amount":10,"sanctionsHit":false,"kycVerified":true,"pep":false}
                        """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TRANSACTION_ALREADY_SCREENED"));
    }

    @Test
    void invalidRequestsAreA400WithAStableCode() throws Exception {
        mockMvc.perform(post("/api/v1/compliance/screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                            {"transactionId":"TX-4","customerId":"C1","amount":-1,"sanctionsHit":false,"kycVerified":true,"pep":false}
                        """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value("amount must be positive"));

        mockMvc.perform(post("/api/v1/compliance/screen")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Malformed request body"));
    }

    /**
     * Maps the repository's translation of the unique transaction_id conflict.
     * The real race is proven against PostgreSQL in ComplianceServiceIT.
     */
    @Test
    void aScreeningRecordedConcurrentlyIsADuplicateRequest() throws Exception {
        when(service.screen(any(ComplianceScreeningCommand.class)))
                .thenThrow(new ScreeningAlreadyRecordedException("TX-5", null));

        mockMvc.perform(post("/api/v1/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body("TX-5", "C1", "10")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_REQUEST"));
    }

    @Test
    void anyOtherIntegrityFailureIsAServerErrorWithoutDetails() throws Exception {
        when(service.screen(any(ComplianceScreeningCommand.class)))
                .thenThrow(new DataIntegrityViolationException("ck_compliance_screening_currency violated by row ..."));

        mockMvc.perform(post("/api/v1/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body("TX-6", "C1", "10")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("The screening could not be stored"));
    }

    @Test
    void oversizedIdsBadCurrenciesAndUnstorableAmountsAreA400() throws Exception {
        String longId = "T".repeat(129);
        expectInvalid(body(longId, "C1", "10"), "transactionId must be at most 128 characters");
        expectInvalid(body("TX-7", longId, "10"), "customerId must be at most 128 characters");
        expectInvalid(body("TX-7", "C1", "10.00001"), "amount must have at most 4 decimal places");
        expectInvalid(body("TX-7", "C1", "1234567890123456"), "amount must have at most 15 integer digits");
        expectInvalid("""
                {"transactionId":"TX-7","customerId":"C1","amount":10,"currency":"aed","sanctionsHit":false,"kycVerified":true,"pep":false}
                """, "currency must be an upper-case ISO 4217 code");
    }

    @Test
    void responseStatesThatTheFactsWereCallerAttested() throws Exception {
        ComplianceResult result = ComplianceResultFixtures.result("TX-8", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(service.screen(any(ComplianceScreeningCommand.class))).thenReturn(result);

        mockMvc.perform(post("/api/v1/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body("TX-8", "C1", "10")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.attestation").value("CALLER_ATTESTED"))
                .andExpect(jsonPath("$.sanctionsHit").doesNotExist());
    }

    private void expectInvalid(String body, String message) throws Exception {
        mockMvc.perform(post("/api/v1/compliance/screen").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.message").value(message));
    }

    private static String body(String transactionId, String customerId, String amount) {
        return """
                {"transactionId":"%s","customerId":"%s","amount":%s,"currency":"AED","sanctionsHit":false,"kycVerified":true,"pep":false}
                """.formatted(transactionId, customerId, amount);
    }
}
