package com.bank.compliance.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

interface SpringDataComplianceScreeningRepository extends JpaRepository<ComplianceScreeningJpaEntity, String> {

    Optional<ComplianceScreeningJpaEntity> findByTransactionId(String transactionId);
}
