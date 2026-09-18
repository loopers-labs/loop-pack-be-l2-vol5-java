package com.loopers.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    @Test
    void commerceDomainHasNoPersistenceOrHttpDependencies() {
        var classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers");
        noClasses().that().resideInAPackage("..domain..")
            .and().resideOutsideOfPackages("..domain.example..", "com.loopers.domain")
            .should().dependOnClassesThat()
            .resideInAnyPackage("jakarta.persistence..", "org.springframework.http..", "org.springframework.web..")
            .check(classes);
        noClasses().that().resideInAPackage("..domain..")
            .and().resideOutsideOfPackages("..domain.example..", "com.loopers.domain")
            .should().beAssignableTo(com.loopers.domain.BaseEntity.class)
            .check(classes);
        noClasses().that().resideInAPackage("..support.error..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.http..", "org.springframework.web..")
            .check(classes);
    }

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
}
