package com.loopers.infrastructure.product;

import com.loopers.application.brand.BrandApplicationService;
import com.loopers.application.brand.BrandNotFoundException;
import com.loopers.application.brand.port.BrandRepository;
import com.loopers.application.product.ProductApplicationService;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.product.ProductId;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class ProductPersistenceIntegrationTest {
    @Autowired private ProductApplicationService service;
    @Autowired private BrandApplicationService brands;
    @Autowired private BrandRepository brandRepository;
    @Autowired private ProductRepository repository;
    @Autowired private ProductJpaRepository jpaRepository;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager manager;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() { cleanup.truncateAllTables(); }

    @Test
    @DisplayName("상품 생성과 수정과 재고 설정 후 재조회해도 브랜드와 금액과 재고가 유지된다")
    void persistsChanges() {
        long brandId = brands.create("브랜드").id().value();
        new TransactionTemplate(manager).executeWithoutResult(status -> {
            var saved = service.create(brandId, "상품", 100, 5);
            service.change(saved.id(), "수정", 200);
            service.setStock(saved.id(), 0);
            entityManager.flush();
            entityManager.clear();
            var found = service.getAdminProduct(saved.id());
            assertThat(found.brandId()).isEqualTo(brandId);
            assertThat(found.name()).isEqualTo("수정");
            assertThat(found.price()).isEqualTo(200);
            assertThat(found.stock()).isZero();
        });
    }

    @Test
    @DisplayName("없는 브랜드와 삭제된 브랜드로 상품을 생성하면 저장하지 않는다")
    void rejectsUnavailableBrand() {
        assertThatThrownBy(() -> service.create(999, "상품", 0, 0)).isInstanceOf(BrandNotFoundException.class);
        var brand = brands.create("브랜드");
        brandRepository.save(Brand.restore(brand.id(), brand.name(), true));
        assertThatThrownBy(() -> service.create(brand.id().value(), "상품", 0, 0))
            .isInstanceOf(BrandNotFoundException.class);
        assertThat(jpaRepository.count()).isZero();
    }

    @Test
    @DisplayName("삭제된 상품은 재고 변경을 거절하고 반복 삭제해도 행은 유지된다")
    void softDeletes() {
        long brandId = brands.create("브랜드").id().value();
        var saved = service.create(brandId, "상품", 0, 5);
        service.delete(saved.id());
        service.delete(saved.id());
        assertThatThrownBy(() -> service.setStock(saved.id(), 2)).isInstanceOf(IllegalStateException.class);
        var found = repository.findById(new ProductId(saved.id())).orElseThrow();
        assertThat(found.isDeleted()).isTrue();
        assertThat(found.getStock().value()).isEqualTo(5);
        assertThat(jpaRepository.count()).isEqualTo(1);
    }
}
