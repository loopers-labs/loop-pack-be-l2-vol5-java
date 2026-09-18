package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
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

    @DisplayName("검사 대상이 실제 구현 클래스를 포함한다 — 규칙이 빈손으로 통과하지 않는다.")
    @Test
    void importsRealClasses() {
        JavaClasses classes = productionClasses();

        assertThat(classes).isNotEmpty();
        assertThat(nameCountIn(classes, ".domain.")).isPositive();
        assertThat(nameCountIn(classes, ".application.")).isPositive();
        assertThat(nameCountIn(classes, ".interfaces.")).isPositive();
        assertThat(nameCountIn(classes, ".infrastructure.")).isPositive();
    }

    @DisplayName("층 의존 방향: domain 은 바깥을 모르고, application 은 인프라·표현을 모른다.")
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
            .check(classes);

        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(classes);
    }

    @DisplayName("도메인 모델은 JPA 를 모른다 — 엔티티는 infrastructure 에 있다.")
    @Test
    void domainDoesNotKnowJpa() {
        noClasses().that().resideInAPackage("..domain..")
            .and().haveNameNotMatching("com\\.loopers\\.domain\\.(Base|Audit)Entity")
            .should().dependOnClassesThat().resideInAnyPackage("jakarta.persistence..")
            .check(productionClasses());
    }

    @DisplayName("도메인과 application 은 Spring Data 를 모른다 — 저장소 구현은 infrastructure 의 것이다.")
    @Test
    void onlyInfrastructureKnowsSpringData() {
        noClasses().that().resideInAnyPackage("..domain..", "..application..")
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.data..")
            .check(productionClasses());
    }

    @DisplayName("도메인은 웹을 모른다.")
    @Test
    void domainDoesNotKnowWeb() {
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.web..", "org.springframework.http..", "jakarta.servlet..")
            .check(productionClasses());
    }

    private static long nameCountIn(JavaClasses classes, String packageFragment) {
        return classes.stream()
            .filter(javaClass -> javaClass.getName().contains(packageFragment))
            .count();
    }

    @DisplayName("2.2 · JPA 엔티티는 infrastructure 에 있고 package-private 이다.")
    @Test
    void entitiesStayInsideInfrastructure() {
        JavaClasses classes = productionClasses();

        classes().that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().resideInAPackage("..infrastructure..")
            .check(classes);

        noClasses().that().areAnnotatedWith(jakarta.persistence.Entity.class)
            .should().bePublic()
            .check(classes);
    }

    @DisplayName("2.2 · Spring Data 리포지토리는 infrastructure 안에서 package-private 이다.")
    @Test
    void springDataRepositoriesStayInsideInfrastructure() {
        JavaClasses classes = productionClasses();

        classes().that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .should().resideInAPackage("..infrastructure..")
            .check(classes);

        noClasses().that().areAssignableTo(org.springframework.data.repository.Repository.class)
            .should().bePublic()
            .check(classes);
    }

    @DisplayName("8.1 · 도메인 패키지 사이에 순환 의존이 없다.")
    @Test
    void domainPackagesAreFreeOfCycles() {
        slices().matching("com.loopers.domain.(*)..")
            .should().beFreeOfCycles()
            .check(productionClasses());
    }

    @DisplayName("3.10 · 도메인은 HTTP 를 아는 실패 타입을 쓰지 않는다.")
    @Test
    void domainDoesNotKnowHttpAwareFailures() {
        JavaClasses classes = productionClasses();

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.loopers.support.error.ErrorType")
            .check(classes);

        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName("com.loopers.support.error.CoreException")
            .check(classes);
    }

    @DisplayName("6.1 · 트랜잭션 경계는 application 에만 있다.")
    @Test
    void transactionsStartOnlyInApplication() {
        noMethods().that().areDeclaredInClassesThat().resideOutsideOfPackage("..application..")
            .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .check(productionClasses());

        noClasses().that().resideOutsideOfPackage("..application..")
            .should().beAnnotatedWith(org.springframework.transaction.annotation.Transactional.class)
            .check(productionClasses());
    }

    @DisplayName("8.1 · application 은 저장소를 직접 주입받지 않는다.")
    @Test
    void applicationGoesThroughDomainServices() {
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
            .check(productionClasses());
    }

    @DisplayName("6.1 · 도메인은 spring-tx 를 모른다.")
    @Test
    void domainDoesNotKnowSpringTransactions() {
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("org.springframework.transaction..", "jakarta.transaction..")
            .check(productionClasses());
    }

    @DisplayName("필드 주입을 쓰지 않는다.")
    @Test
    void usesConstructorInjectionOnly() {
        noFields().that().areDeclaredInClassesThat().resideInAPackage("com.loopers..")
            .should().beAnnotatedWith(org.springframework.beans.factory.annotation.Autowired.class)
            .check(productionClasses());
    }
}
