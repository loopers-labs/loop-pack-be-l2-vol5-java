package com.loopers.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.loopers", importOptions = ImportOption.DoNotIncludeTests.class)
class LayerArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_DEPENDENCY_RULE = noClasses()
            .that().resideInAPackage("com.loopers.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.loopers.application..",
                    "com.loopers.interfaces..",
                    "com.loopers.infrastructure.."
            );

    @ArchTest
    static final ArchRule APPLICATION_DEPENDENCY_RULE = noClasses()
            .that().resideInAPackage("com.loopers.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.loopers.interfaces..",
                    "com.loopers.infrastructure.."
            );

    @ArchTest
    static final ArchRule INTERFACES_DEPENDENCY_RULE = noClasses()
            .that().resideInAPackage("com.loopers.interfaces..")
            .should().dependOnClassesThat().resideInAPackage("com.loopers.infrastructure..");

    @ArchTest
    static final ArchRule INFRASTRUCTURE_INTERFACES_DEPENDENCY_RULE = noClasses()
            .that().resideInAPackage("com.loopers.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("com.loopers.interfaces..");

    @ArchTest
    static final ArchRule INFRASTRUCTURE_APPLICATION_SERVICE_DEPENDENCY_RULE = noClasses()
            .that().resideInAPackage("com.loopers.infrastructure..")
            .should().dependOnClassesThat()
            .haveNameMatching("com\\.loopers\\.application\\..*Service");

    @ArchTest
    static final ArchRule JDBC_DEPENDENCY_RULE = noClasses()
            .that().resideInAnyPackage(
                    "com.loopers.domain..",
                    "com.loopers.application..",
                    "com.loopers.interfaces..",
                    "com.loopers.infrastructure.."
            )
            .and().haveSimpleNameNotStartingWith("Jdbc")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.jdbc..");
}
