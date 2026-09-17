package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.base.DescribedPredicate.describe;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers");

    @Test
    @DisplayName("도메인은 외부 계층과 Spring·JPA·HTTP에 의존하지 않는다")
    void domainRemainsIndependent() {
        var domain = CLASSES.that(resideInAPackage("..domain.."))
            .that(describe("신규 도메인", type -> !isLegacyExample(type.getPackageName())
                && !type.getName().equals("com.loopers.domain.BaseEntity")));
        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..",
                "org.springframework..", "jakarta.persistence..", "javax.persistence..",
                "jakarta.servlet..", "java.net.http..", "..support.error..")
            .check(domain);
    }

    @Test
    @DisplayName("애플리케이션은 HTTP 계층과 저장 구현에 의존하지 않는다")
    void applicationUsesPorts() {
        var application = CLASSES.that(resideInAPackage("..application.."))
            .that(describe("Example 제외", type -> !isLegacyExample(type.getPackageName())));

        noClasses().should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(application);
    }

    @Test
    @DisplayName("HTTP 계층은 저장 어댑터를 직접 참조하지 않는다")
    void interfacesDoNotAccessInfrastructure() {
        var interfaces = CLASSES.that(resideInAPackage("..interfaces.."))
            .that(describe("Example 제외", type -> !isLegacyExample(type.getPackageName())));

        noClasses().should().dependOnClassesThat()
            .resideInAPackage("..infrastructure..")
            .check(interfaces);
    }

    private static boolean isLegacyExample(String packageName) {
        return packageName.contains(".example.") || packageName.endsWith(".example");
    }
}
