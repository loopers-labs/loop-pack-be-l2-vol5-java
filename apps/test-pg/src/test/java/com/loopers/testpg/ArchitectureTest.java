package com.loopers.testpg;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void keepsLayersAndCommerceIndependent() {
        var classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers.testpg");

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..application..", "..interfaces..", "..infrastructure..", "org.springframework..", "lombok..")
            .check(classes);
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(classes);
        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(classes);
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("com.loopers.domain..", "com.loopers.application..", "com.loopers.infrastructure..", "jakarta.persistence..")
            .check(classes);
    }
}
