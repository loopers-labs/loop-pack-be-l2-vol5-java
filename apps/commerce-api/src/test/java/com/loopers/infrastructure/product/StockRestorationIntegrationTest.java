package com.loopers.infrastructure.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.product.StockRestoration;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class StockRestorationIntegrationTest {

    private final StockRestoration stockRestoration;
    private final ProductService productService;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final TransactionTemplate transactionTemplate;
    private final EntityManager entityManager;
    private final DatabaseCleanUp databaseCleanUp;

    private Long productId;

    @Autowired
    StockRestorationIntegrationTest(
        StockRestoration stockRestoration,
        ProductService productService,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        TransactionTemplate transactionTemplate,
        EntityManager entityManager,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.stockRestoration = stockRestoration;
        this.productService = productService;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.transactionTemplate = transactionTemplate;
        this.entityManager = entityManager;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        productId = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        productFacade.adjustStock(productId, Quantity.of(3));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private void restore(int amount) {
        transactionTemplate.executeWithoutResult(status -> stockRestoration.restoreStock(productId, Quantity.of(amount)));
    }

    private long storedQuantity() {
        return ((Number) entityManager.createNativeQuery("select quantity from product where id = :id")
            .setParameter("id", productId).getSingleResult()).longValue();
    }

    @DisplayName("RSV-003: 반환한 수량만큼 가용 재고가 늘어난다")
    @Test
    void restores() {
        restore(2);

        assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(5));
    }

    @DisplayName("RSV-007: 삭제된 상품에도 반환하고, 상품은 계속 보이지 않는다")
    @Test
    void restoresDeleted() {
        productFacade.delete(productId);

        restore(2);

        assertThat(storedQuantity()).isEqualTo(5);
        assertThatThrownBy(() -> productService.get(productId))
            .isInstanceOf(DomainException.class)
            .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
    }
}
