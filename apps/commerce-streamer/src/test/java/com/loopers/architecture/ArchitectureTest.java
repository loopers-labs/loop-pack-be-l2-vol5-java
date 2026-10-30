package com.loopers.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** commerce-api 와 같은 layer-first 금지 의존을 streamer 에도 적용한다(소비 설계 2절). */
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

        noClasses().that().resideInAPackage("..infrastructure..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..interfaces..", "..application..")
                .check(classes);
    }
}
