package com.loopers.domain.product;

import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ProductRepositoryTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("삭제되지 않은 상품을 id로 조회할 때, ")
    @Nested
    class FindActiveById {
        @DisplayName("저장된 상품은, flush/clear 후 재조회해도 동일한 값으로 조회된다.")
        @Test
        void returnsProduct_afterFlushAndClear() {
            // arrange
            ProductModel saved = productRepository.save(new ProductModel(1L, "runner", 10_000L, 5));
            Long id = saved.getId();
            entityManager.flush();
            entityManager.clear();

            // act
            Optional<ProductModel> result = productRepository.findActiveById(id);

            // assert
            assertThat(result).isPresent();
            assertThat(result.get().getName()).isEqualTo("runner");
        }

        @DisplayName("삭제된 상품은, 조회되지 않는다.")
        @Test
        void returnsEmpty_whenProductIsDeleted() {
            // arrange
            ProductModel saved = productRepository.save(new ProductModel(1L, "runner", 10_000L, 5));
            saved.delete();
            productJpaRepository.save(saved);
            entityManager.flush();
            entityManager.clear();

            // act
            Optional<ProductModel> result = productRepository.findActiveById(saved.getId());

            // assert
            assertThat(result).isEmpty();
        }

        @DisplayName("존재하지 않는 id로 조회하면, 빈 결과를 반환한다.")
        @Test
        void returnsEmpty_whenIdDoesNotExist() {
            // act
            Optional<ProductModel> result = productRepository.findActiveById(999L);

            // assert
            assertThat(result).isEmpty();
        }
    }

    @DisplayName("브랜드에 연결된 삭제되지 않은 상품이 있는지 확인할 때, ")
    @Nested
    class ExistsActiveByBrandId {
        @DisplayName("삭제되지 않은 상품이 있으면, true를 반환한다.")
        @Test
        void returnsTrue_whenActiveProductExists() {
            // arrange
            productRepository.save(new ProductModel(1L, "runner", 10_000L, 5));

            // act
            boolean result = productRepository.existsActiveByBrandId(1L);

            // assert
            assertThat(result).isTrue();
        }

        @DisplayName("연결된 상품이 모두 삭제됐으면, false를 반환한다.")
        @Test
        void returnsFalse_whenAllProductsAreDeleted() {
            // arrange
            ProductModel saved = productRepository.save(new ProductModel(1L, "runner", 10_000L, 5));
            saved.delete();
            productJpaRepository.save(saved);
            entityManager.flush();
            entityManager.clear();

            // act
            boolean result = productRepository.existsActiveByBrandId(1L);

            // assert
            assertThat(result).isFalse();
        }
    }
}
