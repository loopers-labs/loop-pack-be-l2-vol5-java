package com.loopers.infrastructure.persistence.mall.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.support.test.IntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

@IntegrationTest
class ProductRepositoryIntegrationTest {
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private EntityManager entityManager;

    @DisplayName("상품을 저장하고 수정한 뒤 영속성 컨텍스트를 비워도 상태를 보존한다")
    @Test
    @Transactional
    void savesAndUpdatesProduct() {
        Product product = productRepository.save(Product.create(1L, "상품", "설명", 1_000L, 5));
        product.update("변경", null, 2_000L);
        product.setStock(0);
        productRepository.save(product);
        entityManager.flush();
        entityManager.clear();

        Product restored = productRepository.findById(product.getId()).orElseThrow();

        assertThat(restored.getName()).isEqualTo("변경");
        assertThat(restored.getDescription()).isNull();
        assertThat(restored.getPrice()).isEqualTo(2_000L);
        assertThat(restored.getStock()).isZero();
        assertThat(restored.getCreatedAt()).isCloseTo(product.getCreatedAt(), within(1, java.time.temporal.ChronoUnit.MICROS));
    }
}
