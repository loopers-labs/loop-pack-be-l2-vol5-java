package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers");

    @DisplayName("domain 레이어는 batch(진입점), infrastructure 레이어에 의존하지 않는다.")
    @Test
    void domainDoesNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("com.loopers.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.loopers.batch..", "com.loopers.infrastructure..")
            .check(CLASSES);
    }

    @DisplayName("batch(진입점) 레이어는 infrastructure 레이어에 의존하지 않는다.")
    @Test
    void batchDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("com.loopers.batch..")
            .should().dependOnClassesThat().resideInAPackage("com.loopers.infrastructure..")
            .check(CLASSES);
    }
}
