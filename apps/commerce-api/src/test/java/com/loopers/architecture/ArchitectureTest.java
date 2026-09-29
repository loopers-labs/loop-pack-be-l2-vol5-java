package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static JavaClasses classes;

    @BeforeAll
    static void setUp() {
        classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers");
    }

    @DisplayName("domain 은 interfaces, application, infrastructure 에 의존하지 않는다.")
    @Test
    void domainDoesNotDependOnOuterLayers() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..");

        rule.check(classes);
    }

    @DisplayName("application 은 interfaces, infrastructure 에 의존하지 않는다.")
    @Test
    void applicationDoesNotDependOnInterfacesOrInfrastructure() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..");

        rule.check(classes);
    }

    @DisplayName("interfaces 는 infrastructure 에 의존하지 않는다.")
    @Test
    void interfacesDoesNotDependOnInfrastructure() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..infrastructure..");

        rule.check(classes);
    }
}
