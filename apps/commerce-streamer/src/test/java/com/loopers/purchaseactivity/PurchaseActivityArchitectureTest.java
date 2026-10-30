package com.loopers.purchaseactivity;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import org.junit.jupiter.api.Test;

class PurchaseActivityArchitectureTest {
    @Test
    void 구매_활동_도메인과_애플리케이션은_영속성_구현을_직접_참조하지_않는다() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.loopers.purchaseactivity");

        noClasses().that().resideInAPackage("..domain..")
                .should().dependOnClassesThat()
                .resideInAnyPackage("..application..", "..infrastructure..", "..interfaces..", "jakarta.persistence..")
                .check(classes);
        noClasses().that().resideInAPackage("..application..")
                .should().dependOnClassesThat().resideInAnyPackage("..infrastructure..", "..interfaces..")
                .check(classes);
        noClasses().that().resideInAPackage("..interfaces..")
                .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
                .check(classes);
    }
}
