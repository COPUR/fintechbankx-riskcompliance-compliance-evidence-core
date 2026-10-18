package com.bank.compliance.application;

import com.bank.compliance.domain.Attestation;
import com.bank.compliance.domain.AttestationSource;
import com.bank.compliance.domain.ComplianceDecision;
import com.bank.compliance.domain.ComplianceResult;
import com.bank.compliance.domain.ComplianceResultFixtures;
import com.bank.compliance.domain.ComplianceScreenedEvent;
import com.bank.compliance.domain.CustomerNotFoundException;
import com.bank.compliance.domain.CustomerProfile;
import com.bank.compliance.domain.KycStatus;
import com.bank.compliance.domain.ListVersion;
import com.bank.compliance.domain.NormalizedName;
import com.bank.compliance.domain.ScreeningFacts;
import com.bank.compliance.domain.ScreeningList;
import com.bank.compliance.domain.ScreeningListMatch;
import com.bank.compliance.domain.ScreeningSourceUnavailableException;
import com.bank.compliance.domain.TransactionAlreadyScreenedException;
import com.bank.compliance.domain.port.in.ComplianceScreeningCommand;
import com.bank.compliance.domain.port.in.ComputedFactsScreeningCommand;
import com.bank.compliance.domain.port.out.ComplianceEventPublisher;
import com.bank.compliance.domain.port.out.ComplianceResultRepository;
import com.bank.compliance.domain.port.out.CustomerProfilePort;
import com.bank.compliance.domain.port.out.NameScreeningPort;
import com.bank.compliance.domain.service.ComplianceRuleService;
import com.bank.compliance.domain.service.ListFreshnessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ComplianceScreeningServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-18T12:00:00Z");
    private static final Attestation REQUESTED_BY_PAYMENTS = Attestation.computed("svc-pay-initiation-settlement");
    private static final ListVersion UN = new ListVersion(ScreeningList.SANCTIONS, "UN", "2026-10-17", NOW.minus(Duration.ofHours(3)));
    private static final ListVersion OFAC = new ListVersion(ScreeningList.SANCTIONS, "OFAC-SDN", "1842", NOW.minus(Duration.ofHours(2)));
    private static final ListVersion UAE_PEP = new ListVersion(ScreeningList.PEP, "UAE-LOCAL", "2026-10", NOW.minus(Duration.ofDays(10)));
    private static final CustomerProfile VERIFIED_CUSTOMER = new CustomerProfile("C1", "Test", "Clean Person", KycStatus.VERIFIED);
    private static final NormalizedName CLEAN_NAME = NormalizedName.of("TEST CLEAN PERSON");

    @Mock
    private ComplianceRuleService ruleService;

    @Mock
    private ComplianceResultRepository repository;

    @Mock
    private ComplianceEventPublisher eventPublisher;

    @Mock
    private CustomerProfilePort customerProfiles;

    @Mock
    private NameScreeningPort nameScreening;

    private ComplianceScreeningService service;

    @BeforeEach
    void setUp() {
        service = new ComplianceScreeningService(ruleService, repository, eventPublisher, customerProfiles, nameScreening,
                new ListFreshnessPolicy(Duration.ofDays(1), Duration.ofDays(35)), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void shouldReturnExistingResultWhenAlreadyScreened() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-1", "C1", new BigDecimal("100"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);
        ComplianceResult existing = ComplianceResultFixtures.result("TX-1", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));

        when(repository.findByTransactionId("TX-1")).thenReturn(Optional.of(existing));

        ComplianceResult result = service.screen(command);

        assertThat(result).isEqualTo(existing);
        verify(ruleService, never()).screen(any());
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void shouldEvaluateAndPersistWhenNoExistingResult() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-2", "C1", new BigDecimal("11000"), "AED", false, true, true, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);
        ComplianceResult evaluated = ComplianceResultFixtures.result("TX-2", "C1", ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"));

        when(repository.findByTransactionId("TX-2")).thenReturn(Optional.empty());
        when(ruleService.screen(command)).thenReturn(evaluated);
        when(repository.save(evaluated)).thenReturn(evaluated);

        ComplianceResult result = service.screen(command);

        assertThat(result).isEqualTo(evaluated);
        verify(ruleService).screen(command);
        verify(repository).save(evaluated);
    }

    @Test
    void newScreeningPublishesOneScreenedEventAfterTheResultIsSaved() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-5", "C7", new BigDecimal("250.00"), "AED", true, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);
        ComplianceResult evaluated = ComplianceResultFixtures.result("TX-5", "C7", ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"));
        when(repository.findByTransactionId("TX-5")).thenReturn(Optional.empty());
        when(ruleService.screen(command)).thenReturn(evaluated);
        when(repository.save(evaluated)).thenReturn(evaluated);

        service.screen(command);

        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).save(evaluated);
        ArgumentCaptor<ComplianceScreenedEvent> published = ArgumentCaptor.forClass(ComplianceScreenedEvent.class);
        order.verify(eventPublisher).publish(published.capture());
        verifyNoMoreInteractions(eventPublisher);
        assertThat(published.getValue().screeningId()).isEqualTo(evaluated.getId());
        assertThat(published.getValue().transactionId()).isEqualTo("TX-5");
        assertThat(published.getValue().decision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(published.getValue().reasons()).containsExactly("SANCTIONS_HIT");
    }

    @Test
    void aFailedSavePublishesNothing() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-6", "C7", new BigDecimal("10.00"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);
        ComplianceResult evaluated = ComplianceResultFixtures.result("TX-6", "C7", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(repository.findByTransactionId("TX-6")).thenReturn(Optional.empty());
        when(ruleService.screen(command)).thenReturn(evaluated);
        when(repository.save(evaluated)).thenThrow(new IllegalStateException("unique transaction_id"));

        assertThatThrownBy(() -> service.screen(command)).isInstanceOf(IllegalStateException.class);
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void replayWithDifferentFactsIsRefusedAndTheEvidenceIsKept() {
        // Recorded: 100.00 AED, no sanctions hit, KYC verified, not a PEP (fixture facts).
        ComplianceResult existing = ComplianceResultFixtures.result("TX-7", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(repository.findByTransactionId("TX-7")).thenReturn(Optional.of(existing));
        ComplianceScreeningCommand sanctionsFlipped =
                new ComplianceScreeningCommand("TX-7", "C1", new BigDecimal("100"), "AED", true, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);

        assertThatThrownBy(() -> service.screen(sanctionsFlipped))
                .isInstanceOf(TransactionAlreadyScreenedException.class)
                .message().doesNotContain("TX-7");
        verify(ruleService, never()).screen(any());
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void replayWithTheSameFactsWrittenDifferentlyReturnsTheStoredResult() {
        ComplianceResult existing = ComplianceResultFixtures.result("TX-8", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(repository.findByTransactionId("TX-8")).thenReturn(Optional.of(existing));

        assertThat(service.screen(new ComplianceScreeningCommand("TX-8", "C1", new BigDecimal("100.0000"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS)))
                .isSameAs(existing);
    }

    @Test
    void theSameFactsFromAnotherCallerAreRefusedNotHandedTheStoredResult() {
        ComplianceResult existing = ComplianceResultFixtures.result("TX-9", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(repository.findByTransactionId("TX-9")).thenReturn(Optional.of(existing));
        ComplianceScreeningCommand fromAnAdmin = new ComplianceScreeningCommand("TX-9", "C1", new BigDecimal("100.00"), "AED",
                false, true, false, com.bank.compliance.domain.Attestation.byStaff("8d0c6a4e-2f7b-4c1e-9a43-5b2f0d9e7c11"));

        assertThatThrownBy(() -> service.screen(fromAnAdmin)).isInstanceOf(TransactionAlreadyScreenedException.class);
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void findByTransactionIdShouldDelegate() {
        when(repository.findByTransactionId("TX-3")).thenReturn(Optional.empty());

        assertThat(service.findByTransactionId("TX-3")).isEmpty();
        verify(repository).findByTransactionId("TX-3");
    }

    @Test
    void shouldRefuseAReusedTransactionIdForAnotherCustomer() {
        ComplianceScreeningCommand command = new ComplianceScreeningCommand("TX-9", "C2", new BigDecimal("100"), "AED", false, true, false, com.bank.compliance.domain.ComplianceResultFixtures.PAYMENTS);
        ComplianceResult existing = ComplianceResultFixtures.result("TX-9", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"));
        when(repository.findByTransactionId("TX-9")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.screen(command))
                .isInstanceOf(TransactionAlreadyScreenedException.class)
                .message().doesNotContain("TX-9");
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    // ---- v2: compliance computes sanctions, PEP and KYC from their owners ----

    private static ComputedFactsScreeningCommand computed(String transactionId, String amount, String currency) {
        return new ComputedFactsScreeningCommand(transactionId, "C1", new BigDecimal(amount), currency, REQUESTED_BY_PAYMENTS);
    }

    private static ComplianceResult computedResult(String transactionId, ScreeningFacts facts, ComplianceDecision decision,
                                                   List<String> reasons, List<ListVersion> lists) {
        return ComplianceResult.create(transactionId, "C1", facts, decision, reasons, ComplianceRuleService.RULE_SET_VERSION, lists);
    }

    @Test
    void computedFactsComeFromTheCustomerAndTheListsAndTheResultIsSavedThenPublished() {
        ComputedFactsScreeningCommand command = computed("TX-20", "12000.00", "USD");
        when(repository.findByTransactionId("TX-20")).thenReturn(Optional.empty());
        when(customerProfiles.findCustomer("C1")).thenReturn(Optional.of(VERIFIED_CUSTOMER));
        when(nameScreening.match(ScreeningList.SANCTIONS, CLEAN_NAME)).thenReturn(new ScreeningListMatch(true, List.of(UN, OFAC)));
        when(nameScreening.match(ScreeningList.PEP, CLEAN_NAME)).thenReturn(new ScreeningListMatch(false, List.of(UAE_PEP)));
        ScreeningFacts expectedFacts = new ScreeningFacts(new BigDecimal("12000.00"), "USD", true, true, false, REQUESTED_BY_PAYMENTS);
        ComplianceResult evaluated = computedResult("TX-20", expectedFacts, ComplianceDecision.FAIL, List.of("SANCTIONS_HIT"), List.of(UN, OFAC, UAE_PEP));
        when(ruleService.screen(eq("TX-20"), eq("C1"), eq(expectedFacts), eq(List.of(UN, OFAC, UAE_PEP)))).thenReturn(evaluated);
        when(repository.save(evaluated)).thenReturn(evaluated);

        ComplianceResult result = service.screenWithComputedFacts(command);

        assertThat(result).isSameAs(evaluated);
        assertThat(result.getAttestation()).isEqualTo(AttestationSource.COMPUTED);
        assertThat(result.getAttestedBy()).isEqualTo("svc-pay-initiation-settlement");
        InOrder order = inOrder(repository, eventPublisher);
        order.verify(repository).save(evaluated);
        ArgumentCaptor<ComplianceScreenedEvent> published = ArgumentCaptor.forClass(ComplianceScreenedEvent.class);
        order.verify(eventPublisher).publish(published.capture());
        assertThat(published.getValue().decision()).isEqualTo(ComplianceDecision.FAIL);
        assertThat(published.getValue().screeningId()).isEqualTo(evaluated.getId());
        verify(ruleService, never()).screen(any(ComplianceScreeningCommand.class));
    }

    @Test
    void aPendingKycAndAPepMatchBecomeTheFactsTheRulesJudge() {
        ComputedFactsScreeningCommand command = computed("TX-21", "50.00", "AED");
        when(repository.findByTransactionId("TX-21")).thenReturn(Optional.empty());
        when(customerProfiles.findCustomer("C1")).thenReturn(Optional.of(new CustomerProfile("C1", "Test", "Pep Pérson", KycStatus.PENDING)));
        NormalizedName pepName = NormalizedName.of("TEST PEP PERSON");
        when(nameScreening.match(ScreeningList.SANCTIONS, pepName)).thenReturn(new ScreeningListMatch(false, List.of(UN)));
        when(nameScreening.match(ScreeningList.PEP, pepName)).thenReturn(new ScreeningListMatch(true, List.of(UAE_PEP)));
        ArgumentCaptor<ScreeningFacts> facts = ArgumentCaptor.forClass(ScreeningFacts.class);
        ComplianceResult evaluated = computedResult("TX-21",
                new ScreeningFacts(new BigDecimal("50.00"), "AED", false, false, true, REQUESTED_BY_PAYMENTS),
                ComplianceDecision.FAIL, List.of("KYC_NOT_VERIFIED"), List.of(UN, UAE_PEP));
        when(ruleService.screen(eq("TX-21"), eq("C1"), facts.capture(), anyList())).thenReturn(evaluated);
        when(repository.save(evaluated)).thenReturn(evaluated);

        service.screenWithComputedFacts(command);

        assertThat(facts.getValue().sanctionsHit()).isFalse();
        assertThat(facts.getValue().kycVerified()).as("PENDING is not verified").isFalse();
        assertThat(facts.getValue().pep()).isTrue();
        assertThat(facts.getValue().amount()).isEqualByComparingTo("50.00");
        assertThat(facts.getValue().currency()).isEqualTo("AED");
        assertThat(facts.getValue().attestation()).isEqualTo(REQUESTED_BY_PAYMENTS);
    }

    @Test
    void anUnknownCustomerIsRefusedAndNothingIsStoredOrPublished() {
        when(repository.findByTransactionId("TX-22")).thenReturn(Optional.empty());
        when(customerProfiles.findCustomer("C1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.screenWithComputedFacts(computed("TX-22", "10.00", "USD")))
                .isInstanceOf(CustomerNotFoundException.class)
                .message().doesNotContain("C1").doesNotContain("TX-22");
        verifyNoInteractions(nameScreening, ruleService, eventPublisher);
        verify(repository, never()).save(any());
    }

    @Test
    void anUnreachableCustomerServiceStopsTheScreeningBeforeAnyListIsRead() {
        when(repository.findByTransactionId("TX-23")).thenReturn(Optional.empty());
        when(customerProfiles.findCustomer("C1")).thenThrow(new ScreeningSourceUnavailableException("The customer profile service is unreachable"));

        assertThatThrownBy(() -> service.screenWithComputedFacts(computed("TX-23", "10.00", "USD")))
                .isInstanceOf(ScreeningSourceUnavailableException.class);
        verifyNoInteractions(nameScreening, ruleService, eventPublisher);
        verify(repository, never()).save(any());
    }

    @Test
    void aStaleSanctionsListIsASourceOutageNotAPass() {
        when(repository.findByTransactionId("TX-24")).thenReturn(Optional.empty());
        when(customerProfiles.findCustomer("C1")).thenReturn(Optional.of(VERIFIED_CUSTOMER));
        ListVersion staleUn = new ListVersion(ScreeningList.SANCTIONS, "UN", "2026-10-15", NOW.minus(Duration.ofDays(1).plusMinutes(1)));
        when(nameScreening.match(ScreeningList.SANCTIONS, CLEAN_NAME)).thenReturn(new ScreeningListMatch(false, List.of(staleUn)));

        assertThatThrownBy(() -> service.screenWithComputedFacts(computed("TX-24", "10.00", "USD")))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessageContaining("sanctions list is stale");
        verify(ruleService, never()).screen(anyString(), anyString(), any(), anyList());
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void anEmptyPepRegisterIsASourceOutageNotAPass() {
        when(repository.findByTransactionId("TX-25")).thenReturn(Optional.empty());
        when(customerProfiles.findCustomer("C1")).thenReturn(Optional.of(VERIFIED_CUSTOMER));
        when(nameScreening.match(ScreeningList.SANCTIONS, CLEAN_NAME)).thenReturn(new ScreeningListMatch(false, List.of(UN)));
        when(nameScreening.match(ScreeningList.PEP, CLEAN_NAME)).thenReturn(ScreeningListMatch.none());

        assertThatThrownBy(() -> service.screenWithComputedFacts(computed("TX-25", "10.00", "USD")))
                .isInstanceOf(ScreeningSourceUnavailableException.class)
                .hasMessage("The PEP register is empty");
        verify(repository, never()).save(any());
        verify(eventPublisher, never()).publish(any());
    }

    @Test
    void aReplayOfAComputedScreeningReturnsTheStoredResultWithoutConsultingAnySource() {
        ScreeningFacts recorded = new ScreeningFacts(new BigDecimal("12000.00"), "USD", false, true, true, REQUESTED_BY_PAYMENTS);
        ComplianceResult existing = computedResult("TX-26", recorded, ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"), List.of(UN, UAE_PEP));
        when(repository.findByTransactionId("TX-26")).thenReturn(Optional.of(existing));

        assertThat(service.screenWithComputedFacts(computed("TX-26", "12000", "USD"))).isSameAs(existing);
        verifyNoInteractions(customerProfiles, nameScreening, ruleService, eventPublisher);
        verify(repository, never()).save(any());
    }

    @Test
    void aConflictingReplayOfAComputedScreeningIsRefusedAndTheEvidenceIsKept() {
        ScreeningFacts recorded = new ScreeningFacts(new BigDecimal("12000.00"), "USD", false, true, true, REQUESTED_BY_PAYMENTS);
        ComplianceResult existing = computedResult("TX-27", recorded, ComplianceDecision.REVIEW, List.of("PEP_HIGH_VALUE_REVIEW"), List.of(UN, UAE_PEP));
        when(repository.findByTransactionId("TX-27")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.screenWithComputedFacts(computed("TX-27", "12000.01", "USD")))
                .isInstanceOf(TransactionAlreadyScreenedException.class).message().doesNotContain("TX-27");
        assertThatThrownBy(() -> service.screenWithComputedFacts(computed("TX-27", "12000.00", "AED")))
                .isInstanceOf(TransactionAlreadyScreenedException.class);
        assertThatThrownBy(() -> service.screenWithComputedFacts(new ComputedFactsScreeningCommand("TX-27", "C2",
                new BigDecimal("12000.00"), "USD", REQUESTED_BY_PAYMENTS)))
                .isInstanceOf(TransactionAlreadyScreenedException.class);
        // A caller-attested v1 result is not handed out as the answer to a v2 request either.
        when(repository.findByTransactionId("TX-28")).thenReturn(Optional.of(
                ComplianceResultFixtures.result("TX-28", "C1", ComplianceDecision.PASS, List.of("COMPLIANT"))));
        assertThatThrownBy(() -> service.screenWithComputedFacts(computed("TX-28", "100.00", "AED")))
                .isInstanceOf(TransactionAlreadyScreenedException.class);
        verifyNoInteractions(customerProfiles, nameScreening, ruleService, eventPublisher);
        verify(repository, never()).save(any());
    }

    @Test
    void collaboratorsAreRequired() {
        ListFreshnessPolicy policy = ListFreshnessPolicy.defaults();
        Clock clock = Clock.systemUTC();
        assertThatThrownBy(() -> new ComplianceScreeningService(ruleService, repository, eventPublisher, null, nameScreening, policy, clock))
                .hasMessageContaining("customerProfiles");
        assertThatThrownBy(() -> new ComplianceScreeningService(ruleService, repository, eventPublisher, customerProfiles, null, policy, clock))
                .hasMessageContaining("nameScreening");
        assertThatThrownBy(() -> new ComplianceScreeningService(ruleService, repository, eventPublisher, customerProfiles, nameScreening, null, clock))
                .hasMessageContaining("listFreshness");
        assertThatThrownBy(() -> new ComplianceScreeningService(ruleService, repository, eventPublisher, customerProfiles, nameScreening, policy, null))
                .hasMessageContaining("clock");
    }
}
