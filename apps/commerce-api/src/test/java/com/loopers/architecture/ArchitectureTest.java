package com.loopers.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.simpleNameEndingWith;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    /**
     * 설계 5-0 BC → AG 패키지. 패키지는 AG 단위이고 BC 는 이 매핑으로만 존재한다.
     * `com.loopers.domain`(BaseEntity)·`example` 은 BC 가 아니다.
     */
    private static final Map<String, List<String>> AGGREGATES_BY_BC = Map.of(
            "user", List.of("user"),
            "catalog", List.of("brand", "product", "productlike"),
            "point", List.of("point"),
            "order", List.of("order")
    );
    private static final List<String> AGGREGATES = AGGREGATES_BY_BC.values().stream().flatMap(List::stream).toList();

    private static JavaClasses importClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("com.loopers");
    }

    /** `bc` 에 속하지 않는 AG 들의 `layer` 패키지 패턴. */
    private static String[] otherBoundedContextPackages(String layer, String bc) {
        return AGGREGATES_BY_BC.entrySet().stream()
                .filter(e -> !e.getKey().equals(bc))
                .flatMap(e -> e.getValue().stream())
                .map(ag -> ".." + layer + "." + ag + "..")
                .toArray(String[]::new);
    }

    /** `ag` 를 뺀 나머지 AG 들의 domain 패키지 패턴 (같은 BC 포함). */
    private static String[] otherAggregateDomainPackages(String ag) {
        return AGGREGATES.stream()
                .filter(other -> !other.equals(ag))
                .map(other -> "..domain." + other + "..")
                .toArray(String[]::new);
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

    /** 설계 5-8 (BC 간): domain.&lt;A&gt; 는 다른 BC 의 domain.&lt;B&gt; 를 import 하지 않는다. 다른 BC 의 개념은 ID 로만 든다. */
    @Test
    void domainDoesNotDependOnOtherBoundedContextDomain() {
        var classes = importClasses();
        AGGREGATES_BY_BC.forEach((bc, aggregates) -> {
            String[] others = otherBoundedContextPackages("domain", bc);
            for (String ag : aggregates) {
                noClasses().that().resideInAPackage("..domain." + ag + "..")
                        .should().dependOnClassesThat().resideInAnyPackage(others)
                        .check(classes);
            }
        });
    }

    /** 설계 5-8 (BC 간): application.&lt;A&gt; 는 다른 BC 의 domain.&lt;B&gt;.Repository 를 import 하지 않는다 (다른 BC 는 Service 로만). */
    @Test
    void applicationDoesNotDependOnOtherBoundedContextRepository() {
        var classes = importClasses();
        AGGREGATES_BY_BC.forEach((bc, aggregates) -> {
            String[] others = otherBoundedContextPackages("domain", bc);
            for (String ag : aggregates) {
                noClasses().that().resideInAPackage("..application." + ag + "..")
                        .should().dependOnClassesThat(
                                resideInAnyPackage(others).and(simpleNameEndingWith("Repository")))
                        .allowEmptyShould(true) // user 는 Facade 가 없다
                        .check(classes);
            }
        });
    }

    /** 설계 5-8 (BC 간): infrastructure.&lt;A&gt; 는 다른 BC 의 domain.&lt;B&gt; 를 import 하지 않는다. 조인은 같은 BC 안에서만. */
    @Test
    void infrastructureDoesNotDependOnOtherBoundedContextDomain() {
        var classes = importClasses();
        AGGREGATES_BY_BC.forEach((bc, aggregates) -> {
            String[] others = otherBoundedContextPackages("domain", bc);
            for (String ag : aggregates) {
                noClasses().that().resideInAPackage("..infrastructure." + ag + "..")
                        .should().dependOnClassesThat().resideInAnyPackage(others)
                        .check(classes);
            }
        });
    }

    /** 설계 5-8 (AG 간): Model 은 다른 AG 를 모른다. 같은 BC 라도 다른 AG 의 domain 은 import 하지 않고 ID 만 든다. */
    @Test
    void modelDoesNotDependOnOtherAggregate() {
        var classes = importClasses();
        for (String ag : AGGREGATES) {
            noClasses().that().resideInAPackage("..domain." + ag + "..")
                    .and().haveSimpleNameEndingWith("Model")
                    .should().dependOnClassesThat().resideInAnyPackage(otherAggregateDomainPackages(ag))
                    .check(classes);
        }
    }

    /** 설계 5-8 (AG 간): Repository 인터페이스 시그니처에 다른 AG 의 Model 을 넣지 않는다. 조인 결과는 자기 패키지의 읽기용 record 로 반환한다. */
    @Test
    void repositoryDoesNotExposeOtherAggregateModel() {
        var classes = importClasses();
        for (String ag : AGGREGATES) {
            noClasses().that().resideInAPackage("..domain." + ag + "..")
                    .and().haveSimpleNameEndingWith("Repository")
                    .should().dependOnClassesThat(
                            resideInAnyPackage(otherAggregateDomainPackages(ag)).and(simpleNameEndingWith("Model")))
                    .check(classes);
        }
    }

    /** DR-31: 조회 Repository 는 엔티티(Model)를 반환하지 않는다. 결과는 전용 조회 DTO(*View 의 중첩 record)로만. */
    @Test
    void queryRepositoryDoesNotExposeModel() {
        var classes = importClasses();
        noClasses().that().resideInAPackage("..application..query..")
                .and().haveSimpleNameEndingWith("Repository")
                .should().dependOnClassesThat(resideInAPackage("..domain..").and(simpleNameEndingWith("Model")))
                .check(classes);
    }

    /** DR-31: 조회 전용 DTO(*View 와 그 중첩 record)는 엔티티(Model)를 담지 않는다. */
    @Test
    void queryViewDoesNotHoldModel() {
        var classes = importClasses();
        noClasses().that().resideInAPackage("..application..query..")
                .and().haveNameMatching(".*View(\\$.*)?")
                .should().dependOnClassesThat(resideInAPackage("..domain..").and(simpleNameEndingWith("Model")))
                .check(classes);
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
