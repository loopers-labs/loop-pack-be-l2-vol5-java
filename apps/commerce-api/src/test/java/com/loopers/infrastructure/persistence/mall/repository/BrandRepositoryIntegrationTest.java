package com.loopers.infrastructure.persistence.mall.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class BrandRepositoryIntegrationTest {
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private EntityManager entityManager;

    @DisplayName("연결 상품이 없는 브랜드도 삭제 전용 조회로 조회되며 상품 목록은 비어 있다")
    @Test
    @Transactional
    void findsBrandForDeletion_withEmptyProducts_whenNoProductsLinked() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        entityManager.flush();
        entityManager.clear();

        Brand found = brandRepository.findForDeletion(brand.getId()).orElseThrow();

        assertThat(found.getProducts()).isEmpty();
    }

    @DisplayName("브랜드 저장 한 번으로 브랜드와 모든 연결 상품의 삭제 상태가 함께 반영되고 무관한 값은 보존된다")
    @Test
    @Transactional
    void savesBrand_propagatesDeleteToAllProducts_andKeepsUnrelatedFields() {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        Product product1 = productRepository.save(Product.create(brand.getId(), "상품1", "설명", 1_000L, 5));
        Product product2 = productRepository.save(Product.create(brand.getId(), "상품2", null, 2_000L, 0));
        entityManager.flush();
        entityManager.clear();

        Brand forDeletion = brandRepository.findForDeletion(brand.getId()).orElseThrow();
        forDeletion.delete();
        brandRepository.save(forDeletion);
        entityManager.flush();
        entityManager.clear();

        Brand restoredBrand = brandRepository.findById(brand.getId()).orElseThrow();
        Product restoredProduct1 = productRepository.findById(product1.getId()).orElseThrow();
        Product restoredProduct2 = productRepository.findById(product2.getId()).orElseThrow();
        assertThat(restoredBrand.isDeleted()).isTrue();
        assertThat(restoredProduct1.isDeleted()).isTrue();
        assertThat(restoredProduct2.isDeleted()).isTrue();
        assertThat(restoredProduct1.getPrice()).isEqualTo(1_000L);
        assertThat(restoredProduct1.getStock()).isEqualTo(5);
        assertThat(restoredProduct1.getDescription()).isEqualTo("설명");
        assertThat(restoredProduct2.getPrice()).isEqualTo(2_000L);
        assertThat(restoredProduct2.getStock()).isZero();
    }
}
