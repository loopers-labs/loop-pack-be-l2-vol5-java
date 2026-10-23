package com.loopers.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.loopers", importOptions = ImportOption.DoNotIncludeTests.class)
class PgArchitectureTest {
    @ArchTest
    static final ArchRule DOMAIN = noClasses().that().resideInAPackage("com.loopers.domain.pg..")
            .should().dependOnClassesThat().resideInAnyPackage("com.loopers.application..", "com.loopers.interfaces..",
                    "com.loopers.infrastructure..", "org.springframework..", "jakarta.persistence..", "jakarta.servlet..");

    @ArchTest
    static final ArchRule BASE_ENTITY = noClasses().that().resideInAPackage("com.loopers.domain.pg..")
            .should().dependOnClassesThat().haveFullyQualifiedName("com.loopers.domain.BaseEntity");

    @ArchTest
    static final ArchRule APPLICATION = noClasses().that().resideInAPackage("com.loopers.application..")
            .should().dependOnClassesThat().resideInAnyPackage("com.loopers.interfaces..", "com.loopers.infrastructure..");

    @ArchTest
    static final ArchRule INTERFACES = noClasses().that().resideInAPackage("com.loopers.interfaces..")
            .should().dependOnClassesThat().resideInAPackage("com.loopers.infrastructure..");

    @ArchTest
    static final ArchRule INFRASTRUCTURE = noClasses().that().resideInAPackage("com.loopers.infrastructure..")
            .should().dependOnClassesThat().resideInAPackage("com.loopers.interfaces..");

    @ArchTest
    static final ArchRule NO_APPLICATION_SERVICE = noClasses().that().resideInAPackage("com.loopers.infrastructure..")
            .should().dependOnClassesThat().haveNameMatching("com\\.loopers\\.application\\..*Service");
}
