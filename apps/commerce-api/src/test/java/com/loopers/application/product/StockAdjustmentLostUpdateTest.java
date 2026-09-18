package com.loopers.application.product;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StockAdjustmentLostUpdateTest {

    private static final Long USER = 1L;
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final PointFacade pointFacade;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    private Long productId;

    @Autowired
    StockAdjustmentLostUpdateTest(
        OrderFacade orderFacade,
        ProductService productService,
        PointFacade pointFacade,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.pointFacade = pointFacade;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @BeforeEach
    void setUp() {
        Long brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        productId = productFacade.register(brandId, "코트", Price.of(10_000)).getId();
        productFacade.adjustStock(productId, Quantity.of(10));
        pointFacade.charge(USER, ChargeAmount.of(1_000_000), NOW);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("알려진 한계 · 판매 중에 재고를 조정하면 그 사이의 판매가 조용히 사라진다")
    @Test
    void adjustmentOverwritesSalesInBetween() {
        Quantity seenOnScreen = productService.getStock(productId);
        assertThat(seenOnScreen).isEqualTo(Quantity.of(10));

        sellOne();
        sellOne();
        sellOne();
        assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(7));

        productFacade.adjustStock(productId, Quantity.of(100));

        assertThat(productService.getStock(productId))
            .as("지금 동작을 고정한다 — 업무적으로 옳은 값은 97 이다")
            .isEqualTo(Quantity.of(100));
    }

    @DisplayName("주문은 사라지지 않는다 — 3건이 그대로 남아 재고와 어긋난 채로 있다")
    @Test
    void ordersSurviveTheOverwriteAndDisagreeWithStock() {
        sellOne();
        sellOne();
        sellOne();

        productFacade.adjustStock(productId, Quantity.of(100));

        assertThat(orderFacade.findMine(USER)).hasSize(3);
        assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(100));
    }

    private void sellOne() {
        Order order = orderFacade.place(
            new OrderCreateCommand(USER, List.of(new OrderCreateCommand.Line(productId, OrderQuantity.of(1)))),
            NOW);
        orderFacade.confirm(USER, order.getId(), NOW);
    }
}
