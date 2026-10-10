package com.bank.compliance.domain;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ComplianceDomainArchitectureTest {

    @Test
    void domainShouldNotDependOnApplicationOrInfrastructure() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bank.compliance.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("com.bank.compliance.application..", "com.bank.compliance.infrastructure..")
                .allowEmptyShould(true);

        rule.check(new ClassFileImporter().importPackages("com.bank.compliance"));
    }

    @Test
    void domainIsFreeOfFrameworkAndPersistenceTypes() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.bank.compliance.domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("org.springframework..", "jakarta.persistence..", "org.hibernate..",
                        "com.fasterxml.jackson..", "org.apache.kafka..");

        rule.check(new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.bank.compliance.domain"));
    }
}
