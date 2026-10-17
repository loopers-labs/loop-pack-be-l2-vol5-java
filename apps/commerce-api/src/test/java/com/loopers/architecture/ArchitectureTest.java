package com.loopers.architecture;

import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.ProductService;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

class ArchitectureTest {
    private static final JavaClasses CLASSES = new ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.loopers");

    @DisplayName("domain 레이어는 interfaces, application, infrastructure 레이어에 의존하지 않는다.")
    @Test
    void domainDoesNotDependOnOuterLayers() {
        noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..application..", "..infrastructure..")
            .check(CLASSES);
    }

    @DisplayName("application 레이어는 interfaces, infrastructure 레이어에 의존하지 않는다.")
    @Test
    void applicationDoesNotDependOnInterfacesOrInfrastructure() {
        noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat()
            .resideInAnyPackage("..interfaces..", "..infrastructure..")
            .check(CLASSES);
    }

    @DisplayName("상품 저장(ProductRepository.save)은 ProductService 만 호출한다.")
    @Test
    void onlyProductServiceSavesProducts() {
        // 상품 생성은 ProductService.create 를 거치고, 그 유스케이스가 좋아요 수 집계 행도 함께 만든다 (D-33).
        // 저장소를 직접 불러 상품만 만드는 우회로를 막는다.
        noClasses().that().doNotBelongToAnyOf(ProductService.class)
            .should().callMethod(ProductRepository.class, "save", Product.class)
            .check(CLASSES);
    }

    @DisplayName("interfaces 레이어는 infrastructure 레이어에 의존하지 않는다.")
    @Test
    void interfacesDoesNotDependOnInfrastructure() {
        noClasses().that().resideInAPackage("..interfaces..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .check(CLASSES);
    }
}
