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

    @DisplayName("새 상품의 좋아요 수는 0이고, 상품을 수정해 저장해도 외부에서 갱신한 좋아요 수를 덮어쓰지 않는다")
    @Test
    @Transactional
    void keepsLikeCountWrittenOutsideJpa() {
        Product product = productRepository.save(Product.create(1L, "상품", "설명", 1_000L, 5));
        entityManager.flush();
        entityManager.clear();
        assertThat(likeCountOf(product.getId())).isZero();

        Product loaded = productRepository.findById(product.getId()).orElseThrow();
        entityManager.createNativeQuery("UPDATE products SET like_count = 5 WHERE id = :id")
            .setParameter("id", product.getId())
            .executeUpdate();
        loaded.setStock(0);
        productRepository.save(loaded);
        entityManager.flush();
        entityManager.clear();

        assertThat(likeCountOf(product.getId())).isEqualTo(5L);
    }

    @DisplayName("브랜드의 미삭제 상품만 일괄 삭제하고 삭제 건수를 반환하며 이미 삭제된 상품과 다른 브랜드 상품은 그대로 둔다")
    @Test
    @Transactional
    void deletesOnlyActiveProductsOfBrand() {
        Product activeA = productRepository.save(Product.create(10L, "상품A", null, 1_000L, 5));
        Product activeB = productRepository.save(Product.create(10L, "상품B", null, 1_000L, 5));
        Product alreadyDeleted = productRepository.save(Product.create(10L, "삭제된 상품", null, 1_000L, 5));
        alreadyDeleted.delete();
        productRepository.save(alreadyDeleted);
        Product other = productRepository.save(Product.create(20L, "다른 브랜드 상품", null, 1_000L, 5));
        entityManager.flush();
        entityManager.createNativeQuery("UPDATE products SET updated_at = '2000-01-01 00:00:00.000000'")
            .executeUpdate();
        entityManager.clear();

        int deletedCount = productRepository.deleteAllByBrandId(10L);
        entityManager.clear();

        assertThat(deletedCount).isEqualTo(2);
        assertThat(productRepository.findById(activeA.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(productRepository.findById(activeB.getId()).orElseThrow().isDeleted()).isTrue();
        assertThat(productRepository.findById(other.getId()).orElseThrow().isDeleted()).isFalse();
        assertThat(updatedYearOf(activeA.getId())).isGreaterThan(2000);
        assertThat(updatedYearOf(activeB.getId())).isGreaterThan(2000);
        assertThat(updatedYearOf(alreadyDeleted.getId())).isEqualTo(2000);
        assertThat(updatedYearOf(other.getId())).isEqualTo(2000);
    }

    private int updatedYearOf(Long productId) {
        return ((Number) entityManager.createNativeQuery("SELECT YEAR(updated_at) FROM products WHERE id = :id")
            .setParameter("id", productId)
            .getSingleResult()).intValue();
    }

    private long likeCountOf(Long productId) {
        return ((Number) entityManager.createNativeQuery("SELECT like_count FROM products WHERE id = :id")
            .setParameter("id", productId)
            .getSingleResult()).longValue();
    }
}
