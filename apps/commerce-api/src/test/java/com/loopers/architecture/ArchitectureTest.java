package com.loopers.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.ZonedDateTime;

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
    void commerceDomainsDoNotDependOnJpaOrLombok() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "com.loopers.domain.brand",
                        "com.loopers.domain.coupon",
                        "com.loopers.domain.product",
                        "com.loopers.domain.like",
                        "com.loopers.domain.point",
                        "com.loopers.domain.order",
                        "com.loopers.domain.payment",
                        "com.loopers.domain.user");

        noClasses().that().resideInAnyPackage(
                            "..domain.brand..", "..domain.coupon..", "..domain.product..", "..domain.like..",
                            "..domain.point..", "..domain.order..", "..domain.payment..", "..domain.user..")
                   .should().dependOnClassesThat().resideInAPackage("jakarta.persistence..")
                   .check(classes);

        noClasses().that().resideInAnyPackage(
                            "..domain.brand..", "..domain.coupon..", "..domain.product..", "..domain.like..",
                            "..domain.point..", "..domain.order..", "..domain.payment..", "..domain.user..")
                   .should().dependOnClassesThat().haveFullyQualifiedName("com.loopers.domain.BaseEntity")
                   .check(classes);

        noClasses().that().resideInAnyPackage(
                            "..domain.brand..", "..domain.coupon..", "..domain.product..", "..domain.like..",
                            "..domain.point..", "..domain.order..", "..domain.payment..", "..domain.user..")
                   .should().dependOnClassesThat().resideInAPackage("lombok..")
                   .check(classes);
    }

    @Test
    void businessTimeIsReadThroughApplicationTime() {
        var classes = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(
                        "com.loopers.application.brand",
                        "com.loopers.application.coupon",
                        "com.loopers.application.payment",
                        "com.loopers.application.point",
                        "com.loopers.application.product",
                        "com.loopers.domain.brand",
                        "com.loopers.domain.coupon",
                        "com.loopers.domain.payment",
                        "com.loopers.domain.point",
                        "com.loopers.domain.product");

        noClasses().that().resideInAnyPackage(
                            "..application.brand..", "..application.coupon..", "..application.payment..", "..application.point..",
                            "..application.product..", "..domain.brand..", "..domain.coupon..", "..domain.payment..",
                            "..domain.point..", "..domain.product..")
                   .should().callMethod(ZonedDateTime.class, "now", new Class<?>[0])
                   .check(classes);

        noClasses().that().resideInAnyPackage("..domain.coupon..", "..application.coupon..")
                   .should().callMethod(Instant.class, "now", new Class<?>[0])
                   .check(classes);
    }
}
