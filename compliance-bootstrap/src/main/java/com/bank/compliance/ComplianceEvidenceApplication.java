package com.bank.compliance;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * svc-cmp-evidence: compliance screening of transactions and regulatory
 * report evidence, extracted from enterprise-loan-management-system. Payment
 * services ask it to screen a transaction; each decision is stored once.
 *
 * <p>With the first argument {@code migrate} the image runs as the chart's
 * migration Job instead ({@link DatabaseMigration}) and exits.
 */
@SpringBootApplication
public class ComplianceEvidenceApplication {

    public static void main(String[] args) {
        if (DatabaseMigration.isRequested(args)) {
            System.exit(DatabaseMigration.run(DatabaseMigration.arguments(args)));
        }
        SpringApplication.run(ComplianceEvidenceApplication.class, args);
    }
}
