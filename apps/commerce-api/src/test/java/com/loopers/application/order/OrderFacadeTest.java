package com.loopers.application.order;

import com.loopers.application.order.OrderFacade.OrderRequestLine;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.order.FakeOrderRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderErrorCode;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.point.FakePointRepository;
import com.loopers.domain.point.PointErrorCode;
import com.loopers.domain.point.PointService;
import com.loopers.domain.product.FakeProductRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductErrorCode;
import com.loopers.domain.product.ProductErrorDetail;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 주문 · 상품 · 포인트의 협력은 Facade 가 조율하므로 fake 저장 구현을 연결해 DB 없이 확인함 (설계 4.4, 7.2) */
class OrderFacadeTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final ZonedDateTime CREATED_AT = ZonedDateTime.parse("2026-10-06T10:00:00+09:00[Asia/Seoul]");

    /** 주문 생성 뒤 시간을 흘려 만료를 확인하기 위한 시계 */
    private static class MutableClock extends Clock {
        private Instant instant;
        private final ZoneId zone;

        MutableClock(ZonedDateTime start) {
            this.instant = start.toInstant();
            this.zone = start.getZone();
        }

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private FakeProductRepository productRepository;
    private FakePointRepository pointRepository;
    private FakeOrderRepository orderRepository;
    private PointService pointService;
    private MutableClock clock;
    private OrderFacade orderFacade;
    private Brand brand;

    @BeforeEach
    void setUp() {
        FakeBrandRepository brandRepository = new FakeBrandRepository();
        productRepository = new FakeProductRepository();
        pointRepository = new FakePointRepository();
        orderRepository = new FakeOrderRepository();
        pointService = new PointService(pointRepository);
        clock = new MutableClock(CREATED_AT);
        ProductService productService = new ProductService(productRepository, clock);
        orderFacade = new OrderFacade(new OrderService(orderRepository, clock), productService, pointService);
        brand = brandRepository.save(new Brand("브랜드", null));
    }

    private Product saveProduct(long price, int stock) {
        Product product = new Product(brand, "상품", price);
        product.changeStock(stock);
        return productRepository.save(product);
    }

    private Order createOrder(OrderRequestLine... lines) {
        return orderOf(orderFacade.createOrder(USER_ID, List.of(lines)).id());
    }

    private Order confirm(Long userId, Long orderId) {
        return orderOf(orderFacade.confirmOrder(userId, orderId).id());
    }

    private Order orderOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private static OrderRequestLine line(Product product, int quantity) {
        return new OrderRequestLine(product.getId(), quantity);
    }

    private static Long failedProductId(CoreException exception) {
        return ((ProductErrorDetail) exception.getDetail()).productId();
    }

    private void assertNothingChanged(Order order, Product first, int firstStock, Product second, int secondStock, long balance) {
        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(order.getPaymentAmount()).isNull(),
            () -> assertThat(first.getStock()).isEqualTo(firstStock),
            () -> assertThat(second.getStock()).isEqualTo(secondStock),
            () -> assertThat(pointService.getPoint(USER_ID).getBalance()).isEqualTo(balance)
        );
    }

    @DisplayName("주문을 만들 때, ")
    @Nested
    class Create {
        @DisplayName("상품명 · 단가를 스냅샷으로 남긴 DRAFT 를 저장하고, 재고와 포인트는 차감하지 않는다.")
        @Test
        void createsDraftWithoutDeduction() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 10_000L);

            // act
            Order order = createOrder(line(product, 3));

            // assert
            assertAll(
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(order.getItems().get(0).getProductName()).isEqualTo("상품"),
                () -> assertThat(order.getItems().get(0).getUnitPrice()).isEqualTo(1_000L),
                () -> assertThat(product.getStock()).isEqualTo(10),
                () -> assertThat(pointService.getPoint(USER_ID).getBalance()).isEqualTo(10_000L)
            );
        }

        @DisplayName("없거나 삭제된 상품이 있으면, 처음 만난 상품을 알리는 PRODUCT_NOT_FOUND 예외가 발생하고 주문이 만들어지지 않는다. (ORD-01)")
        @Test
        void throwsProductNotFound_whenProductIsMissingOrDeleted() {
            // arrange
            Product active = saveProduct(1_000L, 10);
            Product deleted = saveProduct(1_000L, 10);
            deleted.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> createOrder(line(active, 1), line(deleted, 1)));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND),
                () -> assertThat(failedProductId(result)).isEqualTo(deleted.getId()),
                () -> assertThat(orderRepository.count()).isZero()
            );
        }
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("10,000 원을 충전하고 7,000 원 주문을 확정하면, 재고가 줄고 잔액 3,000 원이 남으며 CONFIRMED 가 된다.")
        @Test
        void confirmsAndDeducts() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(2_500L, 5);
            pointService.charge(USER_ID, 10_000L);
            Order order = createOrder(line(first, 2), line(second, 2));

            // act
            Order confirmed = confirm(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(confirmed.getPaymentAmount()).isEqualTo(7_000L),
                () -> assertThat(confirmed.getPaidAt()).isNotNull(),
                () -> assertThat(first.getStock()).isEqualTo(8),
                () -> assertThat(second.getStock()).isEqualTo(3),
                () -> assertThat(pointService.getPoint(USER_ID).getBalance()).isEqualTo(3_000L)
            );
        }

        @DisplayName("타인의 주문이면, ORDER_NOT_FOUND 예외가 발생한다. (ORD-06)")
        @Test
        void throwsOrderNotFound_whenOrderBelongsToOthers() {
            Order order = createOrder(line(saveProduct(1_000L, 10), 1));

            CoreException result = assertThrows(CoreException.class, () -> confirm(OTHER_USER_ID, order.getId()));

            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND);
        }

        @DisplayName("이미 확정된 주문이면, ORDER_ALREADY_CONFIRMED 예외가 발생하고 재고와 잔액이 다시 줄지 않는다. (ORD-07)")
        @Test
        void throwsAlreadyConfirmed_withoutDeductingAgain() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 10_000L);
            Order order = createOrder(line(product, 1));
            confirm(USER_ID, order.getId());

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order.getId()));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(product.getStock()).isEqualTo(9),
                () -> assertThat(pointService.getPoint(USER_ID).getBalance()).isEqualTo(9_000L)
            );
        }

        @DisplayName("상품이 삭제되었으면, 그 상품을 알리는 PRODUCT_NOT_FOUND 예외가 발생하고 아무것도 바뀌지 않는다. (ORD-09)")
        @Test
        void throwsProductNotFound_whenProductIsDeleted() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 10_000L);
            Order order = createOrder(line(first, 1), line(second, 1));
            second.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order.getId()));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
            assertThat(failedProductId(result)).isEqualTo(second.getId());
            assertNothingChanged(order, first, 10, second, 10, 10_000L);
        }

        @DisplayName("생성 후 가격이 오르거나 내려도, 주문서 합계로 결제된다. (설계 2.3)")
        @ParameterizedTest
        @ValueSource(longs = {1_500L, 800L})
        void paysOrderTotal_whenPriceChanged(long changedPrice) {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 10_000L);
            Order order = createOrder(line(first, 1), line(second, 2));
            second.update("상품", changedPrice);

            // act
            Order confirmed = confirm(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(confirmed.getPaymentAmount()).isEqualTo(3_000L),
                () -> assertThat(pointService.getPoint(USER_ID).getBalance()).isEqualTo(7_000L)
            );
        }

        // 앞 품목의 차감은 트랜잭션 롤백이 되돌리므로, DB 기준의 "재고 · 잔액 그대로"는 OrderV1ApiE2ETest 에서 확인함 (설계 5.4)
        @DisplayName("두 번째 품목의 재고가 부족하면, 그 상품을 알리는 OUT_OF_STOCK 예외가 발생하고 주문은 DRAFT 로 남는다. (ORD-10)")
        @Test
        void throwsOutOfStock_withFailedProduct() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 1);
            pointService.charge(USER_ID, 10_000L);
            Order order = createOrder(line(first, 2), line(second, 2));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order.getId()));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.OUT_OF_STOCK);
            assertThat(failedProductId(result)).isEqualTo(second.getId());
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        }

        @DisplayName("재고와 잔액이 둘 다 부족하면, 재고 부족을 먼저 알린다. (설계 5.3)")
        @Test
        void reportsOutOfStockFirst() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 1);
            Order order = createOrder(line(first, 1), line(second, 2));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order.getId()));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.OUT_OF_STOCK);
        }

        @DisplayName("잔액이 부족하면, INSUFFICIENT_POINT 예외가 발생하고 주문은 DRAFT 로 남는다. (ORD-11)")
        @Test
        void throwsInsufficientPoint() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 1_999L);
            Order order = createOrder(line(first, 1), line(second, 1));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order.getId()));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(PointErrorCode.INSUFFICIENT_POINT);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT);
        }

        @DisplayName("충전한 적 없는 사용자의 0 원 주문은 확정되고, Point 행이 생기지 않는다. (D-30)")
        @Test
        void confirmsZeroTotal_withoutCreatingPointRow() {
            // arrange
            Product free = saveProduct(0L, 5);
            Order order = createOrder(line(free, 2));

            // act
            Order confirmed = confirm(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(confirmed.getPaymentAmount()).isZero(),
                () -> assertThat(free.getStock()).isEqualTo(3),
                () -> assertThat(pointRepository.count()).isZero()
            );
        }

        @DisplayName("생성 후 30분이 지났으면, ORDER_EXPIRED 예외가 발생하고 아무것도 바뀌지 않는다. (ORD-08)")
        @Test
        void throwsExpired_whenThirtyMinutesPassed() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 10_000L);
            Order order = createOrder(line(first, 1), line(second, 1));
            clock.advance(Duration.ofMinutes(30));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order.getId()));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_EXPIRED);
            assertNothingChanged(order, first, 10, second, 10, 10_000L);
        }

        @DisplayName("만료와 삭제된 상품이 함께 있으면, 만료를 먼저 알린다. (설계 5.3)")
        @Test
        void reportsExpiredBeforeDeletedProduct() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            Order order = createOrder(line(product, 1));
            product.delete();
            clock.advance(Duration.ofMinutes(30));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order.getId()));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_EXPIRED);
        }

        @DisplayName("만료 1초 전이면, 확정되고 결제 시각은 확정한 시각이다. (ORD-08)")
        @Test
        void confirms_justBeforeExpiry() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            pointService.charge(USER_ID, 10_000L);
            Order order = createOrder(line(product, 1));
            clock.advance(Duration.ofMinutes(30).minusSeconds(1));

            // act
            Order confirmed = confirm(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(confirmed.getStatus()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(confirmed.getPaidAt()).isEqualTo(CREATED_AT.plusMinutes(30).minusSeconds(1))
            );
        }
    }

    @DisplayName("내 주문 상세를 조회할 때, ")
    @Nested
    class GetMyOrder {
        @DisplayName("주문한 상품이 이후 삭제되었으면, 그 품목은 판매 여부가 false 이고 나머지는 true 다.")
        @Test
        void marksDeletedProductAsNotOnSale() {
            // arrange
            Product active = saveProduct(1_000L, 10);
            Product deleted = saveProduct(1_000L, 10);
            OrderInfo created = orderFacade.createOrder(USER_ID, List.of(
                new OrderRequestLine(active.getId(), 1),
                new OrderRequestLine(deleted.getId(), 1)
            ));
            deleted.delete();

            // act
            OrderDetailInfo result = orderFacade.getMyOrder(USER_ID, created.id());

            // assert
            assertThat(result.items())
                .extracting(OrderDetailInfo.Item::productId, OrderDetailInfo.Item::onSale)
                .containsExactly(tuple(active.getId(), true), tuple(deleted.getId(), false));
        }
    }
}
