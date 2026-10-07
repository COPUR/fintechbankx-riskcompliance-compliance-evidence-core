package com.bank.compliance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-cmp-evidence: compliance screening of transactions and regulatory
 * report evidence, extracted from enterprise-loan-management-system. Payment
 * services ask it to screen a transaction; each decision is stored once.
 */
@SpringBootApplication
public class ComplianceEvidenceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ComplianceEvidenceApplication.class, args);
    }
}
