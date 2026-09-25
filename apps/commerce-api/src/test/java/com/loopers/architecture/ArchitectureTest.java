package com.loopers.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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

    @Test
    void commerceDomainsDoNotDependOnJpa() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "com.loopers.domain.brand",
                        "com.loopers.domain.product",
                        "com.loopers.domain.like",
                        "com.loopers.domain.point",
                        "com.loopers.domain.order",
                        "com.loopers.domain.user");

        noClasses().that().resideInAnyPackage(
                            "..domain.brand..", "..domain.product..", "..domain.like..",
                            "..domain.point..", "..domain.order..", "..domain.user..")
                   .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                   .check(classes);

        noClasses().that().resideInAnyPackage(
                            "..domain.brand..", "..domain.product..", "..domain.like..",
                            "..domain.point..", "..domain.order..", "..domain.user..")
                   .should().dependOnClassesThat().haveFullyQualifiedName("com.loopers.domain.BaseEntity")
                   .check(classes);
    }
}
