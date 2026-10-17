package com.bank.compliance;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * The four ArchUnit rules of the fintechbankx service guardrails (section 3),
 * checked over the production classes of all four modules. This module is the
 * only one whose test classpath sees domain, application and infrastructure.
 */
class HexagonalArchitectureTest {

    private static final String ROOT = "com.bank.compliance";
    private static final String DOMAIN = ROOT + ".domain..";
    private static final String PORT_IN = ROOT + ".domain.port.in..";
    private static final String PORT_OUT = ROOT + ".domain.port.out";
    private static final String APPLICATION = ROOT + ".application..";
    private static final String INFRASTRUCTURE = ROOT + ".infrastructure..";

    private static final Set<String> INBOUND_ADAPTER_ANNOTATIONS = Set.of(
            "org.springframework.web.bind.annotation.RestController",
            "org.springframework.stereotype.Controller",
            "org.springframework.web.bind.annotation.RestControllerAdvice",
            "org.springframework.web.bind.annotation.ControllerAdvice");
    private static final String KAFKA_LISTENER = "org.springframework.kafka.annotation.KafkaListener";

    private static JavaClasses production;

    @BeforeAll
    static void importProductionClasses() {
        production = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .withImportOption(location -> !location.contains("testFixtures"))
                .importPackages(ROOT);
    }

    /** Rule 1: the domain depends on no application, infrastructure, framework or store client. */
    @Test
    void domainIsFreeOfApplicationInfrastructureAndFrameworks() {
        noClasses().that().resideInAPackage(DOMAIN)
                .should().dependOnClassesThat().resideInAnyPackage(
                        APPLICATION, INFRASTRUCTURE,
                        "org.springframework..", "org.springframework.data..",
                        "jakarta.persistence..", "org.hibernate..",
                        "org.apache.kafka..", "org.springframework.kafka..",
                        "com.mongodb..", "org.bson..",
                        "com.fasterxml.jackson..")
                .check(production);
    }

    /** Rule 2: the application layer depends on no infrastructure. */
    @Test
    void applicationIsFreeOfInfrastructure() {
        noClasses().that().resideInAPackage(APPLICATION)
                .should().dependOnClassesThat().resideInAPackage(INFRASTRUCTURE)
                .check(production);
    }

    /** Rule 3a: inbound web and messaging adapters never reach into the application layer. */
    @Test
    void inboundAdaptersDoNotDependOnApplicationClasses() {
        noClasses().that(inboundAdapters())
                .should().dependOnClassesThat().resideInAPackage(APPLICATION)
                .check(production);
    }

    /** Rule 3b: controllers and listeners drive the domain through a use-case port. */
    @Test
    void controllersAndListenersDependOnUseCasePorts() {
        classes().that(controllersAndListeners())
                .should().dependOnClassesThat().resideInAPackage(PORT_IN)
                .check(production);
    }

    /** Rule 4: implementations of outbound ports are infrastructure adapters. */
    @Test
    void outboundPortImplementationsLiveInInfrastructure() {
        classes().that(implementOutboundPort())
                .should().resideInAPackage(INFRASTRUCTURE)
                .check(production);
    }

    /** Use cases are interfaces in domain.port.in, implemented outside the domain. */
    @Test
    void useCasePortsAreInterfaces() {
        classes().that().resideInAPackage(PORT_IN).and().haveSimpleNameEndingWith("UseCase")
                .should().beInterfaces()
                .check(production);
    }

    /** Layout: use-case commands and queries sit next to their use case in domain.port.in. */
    @Test
    void commandsAndQueriesLiveInUseCasePorts() {
        classes().that().resideInAPackage(DOMAIN)
                .and().haveNameMatching(".*(Command|Query)")
                .should().resideInAPackage(PORT_IN)
                .check(production);
    }

    private static DescribedPredicate<JavaClass> inboundAdapters() {
        return DescribedPredicate.describe("are inbound adapters (controllers, controller advice, listeners)",
                c -> c.getPackageName().startsWith(ROOT + ".infrastructure")
                        && (INBOUND_ADAPTER_ANNOTATIONS.stream().anyMatch(c::isAnnotatedWith) || hasKafkaListener(c)));
    }

    private static DescribedPredicate<JavaClass> controllersAndListeners() {
        return DescribedPredicate.describe("are controllers or listeners",
                c -> c.getPackageName().startsWith(ROOT + ".infrastructure")
                        && (c.isAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                        || c.isAnnotatedWith("org.springframework.stereotype.Controller")
                        || hasKafkaListener(c)));
    }

    private static boolean hasKafkaListener(JavaClass c) {
        return c.isAnnotatedWith(KAFKA_LISTENER)
                || c.getMethods().stream().anyMatch(m -> m.isAnnotatedWith(KAFKA_LISTENER));
    }

    private static DescribedPredicate<JavaClass> implementOutboundPort() {
        return DescribedPredicate.describe("implement a domain.port.out interface",
                c -> !c.isInterface() && c.getAllRawInterfaces().stream()
                        .anyMatch(i -> i.getPackageName().equals(PORT_OUT)));
    }
}
