package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    /** 설계 5-0 BC 식별자. `com.loopers.domain`(BaseEntity)·`example` 은 BC 가 아니다. */
    private static final List<String> BOUNDED_CONTEXTS = List.of("user", "catalog", "point", "order");

    private static JavaClasses importClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.loopers");
    }

    @Test
    void respectsLayerDependencies() {
        var classes = importClasses();

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

    /** 설계 5-8: domain.&lt;A&gt; 는 domain.&lt;B&gt; 를 import 하지 않는다. 다른 BC 의 개념은 ID 로만 든다. */
    @Test
    void domainDoesNotDependOnOtherBoundedContextDomain() {
        var classes = importClasses();
        for (String bc : BOUNDED_CONTEXTS) {
            String[] others = BOUNDED_CONTEXTS.stream()
                    .filter(other -> !other.equals(bc))
                    .map(other -> "..domain." + other + "..")
                    .toArray(String[]::new);
            noClasses().that().resideInAPackage("..domain." + bc + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(others)
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    /** 설계 5-8: application.&lt;A&gt; 는 domain.&lt;B&gt;.Repository 를 import 하지 않는다 (다른 BC 는 Service 로만). */
    @Test
    void applicationDoesNotDependOnOtherBoundedContextRepository() {
        var classes = importClasses();
        for (String bc : BOUNDED_CONTEXTS) {
            String[] others = BOUNDED_CONTEXTS.stream()
                    .filter(other -> !other.equals(bc))
                    .map(other -> "..domain." + other + "..")
                    .toArray(String[]::new);
            noClasses().that().resideInAPackage("..application." + bc + "..")
                    .should().dependOnClassesThat(
                            resideInAnyPackage(others).and(simpleNameEndingWith("Repository")))
                    .allowEmptyShould(true)
                    .check(classes);
        }
    }

    /** 설계 5-8: interfaces 는 domain 을 import 하지 않는다 (infrastructure 는 respectsLayerDependencies). */
    @Test
    void interfacesDoNotDependOnDomain() {
        var classes = importClasses();
        noClasses().that().resideInAPackage("..interfaces..")
                .should().dependOnClassesThat().resideInAPackage("..domain..")
                .check(classes);
    }
}
