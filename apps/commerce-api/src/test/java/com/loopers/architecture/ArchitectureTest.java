package com.loopers.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void respectsLayerDependencies() {
        var classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers");

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
            .check(classes);

        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(classes);

        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(classes);
    }

    /**
     * ADR-12: 오류 종류는 HTTP를 모른다.
     * ArchUnit은 직접 참조만 보므로, 도메인이 던지는 ErrorType이 있는 support.error도 함께 막는다.
     */
    @DisplayName("ADR-12 · domain과 support.error는 org.springframework.http에 의존하지 않는다")
    @Test
    void domainAndErrorTypesDoNotDependOnHttp() {
        var classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers");

        noClasses().that().resideInAnyPackage("..domain..", "..support.error..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.http..")
            .check(classes);
    }
}
