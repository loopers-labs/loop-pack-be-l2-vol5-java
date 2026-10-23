package com.loopers.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers");

    @Test
    void respectsLayerDependencies() {
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
            .check(CLASSES);

        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(CLASSES);

        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(CLASSES);
    }

    // 여러 도메인의 협력은 Facade 가 조율함 (설계 4.4, D-11). 엔티티 참조(D-02)는 막지 않음
    @Test
    void domainServicesDoNotDependOnOtherDomainServicesOrRepositories() {
        classes().that().resideInAPackage("..domain..").and().haveSimpleNameEndingWith("Service")
            .should(notDependOnOtherDomainServicesOrRepositories())
            .check(CLASSES);
    }

    private static ArchCondition<JavaClass> notDependOnOtherDomainServicesOrRepositories() {
        return new ArchCondition<>("not depend on ~Service or ~Repository of another domain package") {
            @Override
            public void check(JavaClass origin, ConditionEvents events) {
                for (Dependency dependency : origin.getDirectDependenciesFromSelf()) {
                    JavaClass target = dependency.getTargetClass();
                    boolean otherDomain = target.getPackageName().contains(".domain.")
                        && !featureOf(target).equals(featureOf(origin));
                    boolean serviceOrRepository = target.getSimpleName().endsWith("Service")
                        || target.getSimpleName().endsWith("Repository");
                    if (otherDomain && serviceOrRepository) {
                        events.add(SimpleConditionEvent.violated(dependency, dependency.getDescription()));
                    }
                }
            }
        };
    }

    /** 기능 패키지 안을 종류별로 나눠도 같은 기능으로 보도록 domain 바로 다음 이름으로 비교함. 예: domain.payment.service → payment */
    private static String featureOf(JavaClass javaClass) {
        String afterDomain = javaClass.getPackageName().split("\\.domain\\.", 2)[1];
        return afterDomain.split("\\.")[0];
    }
}
