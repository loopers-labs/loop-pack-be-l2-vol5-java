package com.loopers.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.loopers", importOptions = ImportOption.DoNotIncludeTests.class)
class OrderingArchitectureTest {
    @ArchTest
    static final ArchRule DOMAIN_IS_INDEPENDENT = noClasses()
        .that().resideInAPackage("com.loopers.domain.ordering..")
        .should().dependOnClassesThat().resideInAnyPackage("com.loopers.application..", "com.loopers.infrastructure..",
            "com.loopers.interfaces..", "org.springframework..", "jakarta.persistence..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule APPLICATION_USES_PORTS = noClasses()
        .that().resideInAPackage("com.loopers.application.ordering..")
        .should().dependOnClassesThat().resideInAnyPackage("com.loopers.infrastructure..", "com.loopers.interfaces..");

    @ArchTest
    static final ArchRule INFRASTRUCTURE_USES_CONTRACTS = noClasses()
        .that().resideInAPackage("com.loopers.infrastructure.persistence.ordering..")
        .should().dependOnClassesThat().resideInAnyPackage("com.loopers.interfaces..", "com.loopers.application..",
            "org.springframework.jdbc..");

    @ArchTest
    static final ArchRule CONSUMER_USES_APPLICATION = noClasses()
        .that().resideInAPackage("com.loopers.interfaces.consumer.ordering..")
        .should().dependOnClassesThat().resideInAPackage("com.loopers.infrastructure..");
}
