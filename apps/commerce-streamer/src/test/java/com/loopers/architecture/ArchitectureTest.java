package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;
import static org.assertj.core.api.Assertions.assertThat;

class ArchitectureTest {

    private static final String BASE_PACKAGE = "com.loopers";

    private static final ImportOption NOT_TEST_FIXTURES = location ->
        !location.contains("/testFixtures/") && !location.contains("-test-fixtures.jar");

    private static JavaClasses productionClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(NOT_TEST_FIXTURES)
            .importPackages(BASE_PACKAGE);
    }

    private static long nameCountIn(JavaClasses classes, String packageFragment) {
        return classes.stream()
            .filter(javaClass -> javaClass.getName().contains(packageFragment))
            .count();
    }

    @DisplayName("검사 대상에 streamer 의 구매 활동 도메인이 들어 있다. 규칙이 빈손으로 통과하지 않는다")
    @Test
    void importsRealClasses() {
        JavaClasses classes = productionClasses();

        assertThat(nameCountIn(classes, ".domain.purchase.")).isPositive();
        assertThat(nameCountIn(classes, ".interfaces.")).isPositive();
    }

    @DisplayName("층 의존 방향: domain 은 바깥을 모르고, application 은 인프라와 표현을 모른다")
    @Test
    void respectsLayerDependencies() {
        JavaClasses classes = productionClasses();

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
            .check(classes);

        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .allowEmptyShould(true)
            .check(classes);

        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(classes);
    }

    @DisplayName("도메인은 JPA, spring-tx, Spring 이벤트, Kafka 를 모른다")
    @Test
    void domainKnowsNoMechanics() {
        noClasses().that().resideInAPackage("..domain..")
            .and().haveNameNotMatching("com\\.loopers\\.domain\\.(Base|Audit)Entity")
            .should().dependOnClassesThat().resideInAnyPackage(
                "jakarta.persistence..", "org.springframework.transaction..", "jakarta.transaction..",
                "org.springframework.context..", "org.apache.kafka..", "org.springframework.kafka..")
            .check(productionClasses());
    }

    @DisplayName("트랜잭션 경계는 application 에만 있다")
    @Test
    void transactionsStartOnlyInApplication() {
        noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage("..application..")
            .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .check(productionClasses());

        noClasses().that().resideOutsideOfPackage("..application..")
            .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .check(productionClasses());
    }

    @DisplayName("JPA 엔티티와 Spring Data 리포지토리는 infrastructure 안에서 package-private 이다")
    @Test
    void persistenceStaysInsideInfrastructure() {
        JavaClasses classes = productionClasses();

        classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true)
            .check(classes);

        noClasses().that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().bePublic()
            .allowEmptyShould(true)
            .check(classes);

        classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .should().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true)
            .check(classes);

        noClasses().that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .should().bePublic()
            .allowEmptyShould(true)
            .check(classes);
    }

    @DisplayName("도메인 패키지 사이에 순환 의존이 없다")
    @Test
    void domainPackagesAreFreeOfCycles() {
        slices().matching("com.loopers.domain.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true)
            .check(productionClasses());
    }

    @DisplayName("필드 주입을 쓰지 않는다")
    @Test
    void usesConstructorInjectionOnly() {
        noFields().that().areDeclaredInClassesThat().resideInAPackage("com.loopers..")
            .should().beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
            .check(productionClasses());
    }
}
