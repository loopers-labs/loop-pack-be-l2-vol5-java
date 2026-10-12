package com.loopers.application.order;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.product.StockDeduction;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("재고 차감을 조건부 UPDATE 로 바꿔도 같은 경쟁 결과가 나온다")
@Import(ConditionalStockOrderConcurrencyTest.ConditionalStockConfig.class)
class ConditionalStockOrderConcurrencyTest extends OrderConcurrencyTest {

    private final ConditionalStockDeduction conditionalStockDeduction;

    @Autowired
    ConditionalStockOrderConcurrencyTest(
        OrderFacade orderFacade,
        PointFacade pointFacade,
        ProductFacade productFacade,
        BrandFacade brandFacade,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp,
        ConditionalStockDeduction conditionalStockDeduction
    ) {
        super(orderFacade, pointFacade, productFacade, brandFacade, jdbcTemplate, databaseCleanUp);
        this.conditionalStockDeduction = conditionalStockDeduction;
    }

    @DisplayName("PRODUCT-023 · 조건부 UPDATE: 재고 5 에 주문 8건 — 확정 5 · 재고 부족 3 · 기술 오류 0 · 최종 재고 0")
    @Test
    @Override
    void stockRace() throws Exception {
        int before = conditionalStockDeduction.calls();
        super.stockRace();
        assertThat(conditionalStockDeduction.calls() - before)
            .as("확정이 조건부 UPDATE 로 차감했다")
            .isEqualTo(8);
    }

    static class ConditionalStockDeduction implements StockDeduction {

        private final JdbcTemplate jdbcTemplate;
        private final AtomicInteger calls = new AtomicInteger();

        ConditionalStockDeduction(JdbcTemplate jdbcTemplate) {
            this.jdbcTemplate = jdbcTemplate;
        }

        int calls() {
            return calls.get();
        }

        @Override
        public void deductStock(Long productId, Quantity amount) {
            calls.incrementAndGet();
            int updated = jdbcTemplate.update(
                "UPDATE product SET quantity = quantity - ?, updated_at = NOW(6) "
                    + "WHERE id = ? AND deleted_at IS NULL AND quantity >= ?",
                amount.value(), productId, amount.value());
            if (updated == 1) {
                return;
            }
            Integer alive = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM product WHERE id = ? AND deleted_at IS NULL", Integer.class, productId);
            throw new DomainException(alive != null && alive == 1
                ? DomainError.INSUFFICIENT_STOCK
                : DomainError.PRODUCT_NOT_FOUND);
        }
    }

    @TestConfiguration
    static class ConditionalStockConfig {

        @Bean
        @Primary
        ConditionalStockDeduction conditionalStockDeduction(JdbcTemplate jdbcTemplate) {
            return new ConditionalStockDeduction(jdbcTemplate);
        }
    }
}
