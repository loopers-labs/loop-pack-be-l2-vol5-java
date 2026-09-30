package com.loopers.architecture;

import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

class ArchitectureTest {
    /**
     * 기능(com.loopers.{기능}.*)마다 안쪽(domain·application)은 어댑터를 모르고,
     * 들어오는 어댑터는 입력 쪽만, 나가는 어댑터는 출력 포트만 본다 (plan.md 5-2).
     */
    @DisplayName("헥사고날 · 안쪽은 어댑터를 모르고, 어댑터끼리는 서로 모른다")
    @Test
    void respectsHexagonalDependencies() {
        var classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers");

        noClasses().that().resideInAPackage("com.loopers.*.domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.loopers.*.application..", "com.loopers.*.adapter..")
            .check(classes);

        noClasses().that().resideInAPackage("com.loopers.*.application..")
            .should().dependOnClassesThat().resideInAPackage("com.loopers.*.adapter..")
            .check(classes);

        noClasses().that().resideInAPackage("com.loopers.*.adapter.in..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("com.loopers.*.adapter.out..", "com.loopers.*.application.port.out..")
            .check(classes);

        noClasses().that().resideInAPackage("com.loopers.*.adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("com.loopers.*.adapter.in..")
            .check(classes);
    }

    /**
     * 기능끼리 서로를 참조하는 순환이 새로 생기지 않게 한다 (plan.md 5-2 기준 ④).
     * 예외 두 가지 (그 밖의 방향은 like → product → brand 처럼 한쪽으로만 흐른다):
     * ① brand → product: 브랜드 삭제 전에 남은 상품을 확인한다(BRD-02).
     * ② product 조회 어댑터 → like.domain: 상품 목록의 좋아요 수·likes_desc 정렬을 SQL 조인 한 번으로 만든다(CQRS-lite 조회).
     */
    @DisplayName("헥사고날 · 기능 사이에 순환 참조가 없다 (예외: brand → product, product 조회 어댑터 → like.domain)")
    @Test
    void featuresAreFreeOfCycles() {
        var classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers");

        slices().matching("com.loopers.(*)..")
            .should().beFreeOfCycles()
            .ignoreDependency(resideInAPackage("com.loopers.brand.."), resideInAPackage("com.loopers.product.."))
            .ignoreDependency(resideInAPackage("com.loopers.product.adapter.out.persistence.."), resideInAPackage("com.loopers.like.domain.."))
            .check(classes);
    }

    /**
     * ADR-12: 오류 종류는 HTTP를 모른다.
     * ArchUnit은 직접 참조만 보므로, 도메인이 던지는 ErrorType이 있는 support.error도 함께 막는다.
     */
    @DisplayName("ADR-12 · domain과 support.error는 org.springframework.http에 의존하지 않는다")
    @Test
    void domainAndErrorTypesDoNotDependOnHttp() {
        var classes = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.loopers");

        noClasses().that().resideInAnyPackage("..domain..", "..support.error..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.http..")
            .check(classes);
    }
}
