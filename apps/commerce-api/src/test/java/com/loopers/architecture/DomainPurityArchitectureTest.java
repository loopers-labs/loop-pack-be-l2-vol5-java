package com.loopers.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.loopers", importOptions = ImportOption.DoNotIncludeTests.class)
class DomainPurityArchitectureTest {

    private static final String[] PURE_DOMAIN_PACKAGES = {
            "com.loopers.domain.mall..",
            "com.loopers.domain.shopping..",
            "com.loopers.domain.ordering..",
            "com.loopers.domain.pay..",
            "com.loopers.domain.coupon..",
            "com.loopers.domain.shared.."
    };

    @ArchTest
    static final ArchRule FRAMEWORK_DEPENDENCY_RULE = noClasses()
            .that().resideInAnyPackage(PURE_DOMAIN_PACKAGES)
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "jakarta.servlet.."
            )
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule BASE_ENTITY_DEPENDENCY_RULE = noClasses()
            .that().resideInAnyPackage(PURE_DOMAIN_PACKAGES)
            .should().dependOnClassesThat().haveFullyQualifiedName("com.loopers.domain.BaseEntity")
            .allowEmptyShould(true);
}
