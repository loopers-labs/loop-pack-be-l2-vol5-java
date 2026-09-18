package com.loopers.domain.product;

import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class ProductServiceIntegrationTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("상품을 조회할 때, ")
    @Nested
    class Get {
        @DisplayName("존재하며 삭제되지 않은 상품 id를 주면, 해당 상품을 반환한다.")
        @Test
        void returnsProduct_whenIdIsActive() {
            // arrange
            ProductModel saved = productJpaRepository.save(new ProductModel(1L, "runner", 10_000L, 5));

            // act
            ProductModel result = productService.getProduct(saved.getId());

            // assert
            assertThat(result.getName()).isEqualTo("runner");
        }

        @DisplayName("존재하지 않는 id를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenIdDoesNotExist() {
            // act
            CoreException result = assertThrows(CoreException.class, () -> productService.getProduct(999L));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }

        @DisplayName("삭제된 상품의 id를 주면, NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsNotFoundException_whenProductIsDeleted() {
            // arrange
            ProductModel saved = productJpaRepository.save(new ProductModel(1L, "runner", 10_000L, 5));
            saved.delete();
            productJpaRepository.save(saved);

            // act
            CoreException result = assertThrows(CoreException.class, () -> productService.getProduct(saved.getId()));

            // assert
            assertThat(result.getErrorType()).isEqualTo(ErrorType.NOT_FOUND);
        }
    }
}
