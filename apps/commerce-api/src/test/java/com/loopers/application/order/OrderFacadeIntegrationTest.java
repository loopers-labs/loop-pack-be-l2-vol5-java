package com.loopers.application.order;

import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
import com.loopers.application.product.ProductFacade;
import com.loopers.application.brand.BrandFacade;
import com.loopers.application.point.PointFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.ChargeAmount;
import com.loopers.domain.point.PointService;
import com.loopers.domain.point.UserPointRepository;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderFacadeIntegrationTest {

    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-09-14T00:00:00Z");

    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final PointService pointService;
    private final PointFacade pointFacade;
    private final UserPointRepository userPointRepository;
    private final DatabaseCleanUp databaseCleanUp;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;

    private Long brandId;
    private Long productId;

    @Autowired
    OrderFacadeIntegrationTest(
        OrderFacade orderFacade,
        ProductService productService,
        PointService pointService,
        PointFacade pointFacade,
        UserPointRepository userPointRepository,
        DatabaseCleanUp databaseCleanUp,
        BrandFacade brandFacade,
        ProductFacade productFacade
    ) {
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.pointService = pointService;
        this.pointFacade = pointFacade;
        this.userPointRepository = userPointRepository;
        this.databaseCleanUp = databaseCleanUp;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        productId = productFacade.register(brandId, "코트", Price.of(10_000)).getId();
        productFacade.adjustStock(productId, Quantity.of(10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private OrderCreateCommand command(Long userId, int quantity) {
        return new OrderCreateCommand(userId,
            List.of(new OrderCreateCommand.Line(productId, OrderQuantity.of(quantity))));
    }

    @Nested
    @DisplayName("접수 — ORDER-003 · 005 · 007")
    class Place {
        @DisplayName("접수하면 DRAFT 주문이 생기고 재고·잔액은 그대로다")
        @Test
        void placesWithoutDeducting() {
            pointFacade.charge(USER, ChargeAmount.of(50_000), NOW);

            Order order = orderFacade.place(command(USER, 2), NOW);

            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(order.getTotalAmount()).isEqualTo(Money.of(20_000));
            assertThat(productService.getStock(productId)).isEqualTo(Quantity.of(10));
            assertThat(pointService.getBalance(USER)).isEqualTo(Money.of(50_000));
        }

        @DisplayName("ORDER-003 · 삭제된 상품은 접수할 수 없다")
        @Test
        void rejectsDeletedProduct() {
            productFacade.delete(productId);

            assertThatThrownBy(() -> orderFacade.place(command(USER, 1), NOW))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);
        }

        @DisplayName("ORDER-005 · 022 · 접수 후 상품의 가격·이름이 바뀌어도 주문은 그대로다")
        @Test
        void copiesPriceAndNameAtPlacement() {
            Order order = orderFacade.place(command(USER, 2), NOW);

            productFacade.update(productId, "트렌치코트", Price.of(99_000));

            Order found = orderFacade.get(USER, order.getId());
            assertThat(found.getTotalAmount()).isEqualTo(Money.of(20_000));
            assertThat(found.getItems().get(0).productName()).isEqualTo("코트");
            assertThat(found.getItems().get(0).unitPrice()).isEqualTo(Price.of(10_000));
        }

        @DisplayName("ORDER-022 · 접수 후 상품이 삭제되어도 주문 내역의 이름은 남는다")
        @Test
        void keepsNameAfterProductDeleted() {
            Order order = orderFacade.place(command(USER, 1), NOW);

            productFacade.delete(productId);

            assertThat(orderFacade.get(USER, order.getId()).getItems().get(0).productName())
                .isEqualTo("코트");
        }
    }
}
