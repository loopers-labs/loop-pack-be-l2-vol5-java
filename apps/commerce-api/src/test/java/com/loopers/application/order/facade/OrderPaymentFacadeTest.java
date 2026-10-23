package com.loopers.application.order.facade;

import com.loopers.application.order.OrderFacade.OrderRequestLine;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.FakeBrandRepository;
import com.loopers.domain.order.FakeOrderRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderErrorCode;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.enums.PaymentMethod;
import com.loopers.domain.payment.gateway.FakePaymentGateway;
import com.loopers.domain.payment.repository.FakePaymentRepository;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.enums.PaymentErrorCode;
import com.loopers.domain.payment.gateway.PaymentGatewayRequest;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.payment.service.PaymentService;
import com.loopers.domain.payment.enums.PaymentStatus;
import com.loopers.domain.payment.vo.ProviderTransaction;
import com.loopers.domain.payment.enums.ProviderTransactionStatus;
import com.loopers.domain.payment.enums.ReservationStatus;
import com.loopers.domain.product.FakeProductDetailCache;
import com.loopers.domain.product.FakeProductLikeCountRepository;
import com.loopers.domain.product.FakeProductListCache;
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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.order.OrderDetailInfo;
import com.loopers.application.order.OrderInfo;
import com.loopers.application.order.OrderSummaryInfo;
import org.springframework.data.domain.PageRequest;

/** fake 와 PG 대역으로 DB · HTTP 없이 확인함. fake 에는 rollback 이 없어 DB 기준 rollback 은 OrderConfirmTransactionTest 가 확인함 */
class OrderPaymentFacadeTest {

    private static final Long USER_ID = 1L;
    private static final Long OTHER_USER_ID = 2L;
    private static final ZonedDateTime CREATED_AT = ZonedDateTime.parse("2026-10-06T10:00:00+09:00[Asia/Seoul]");

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
    private FakeOrderRepository orderRepository;
    private FakePaymentRepository paymentRepository;
    private FakePaymentGateway gateway;
    private FakeProductListCache productListCache;
    private FakeProductDetailCache productDetailCache;
    private MutableClock clock;
    private OrderFacade orderFacade;
    private OrderPaymentFacade orderPaymentFacade;
    private Brand brand;

    @BeforeEach
    void setUp() {
        FakeBrandRepository brandRepository = new FakeBrandRepository();
        productRepository = new FakeProductRepository();
        orderRepository = new FakeOrderRepository();
        paymentRepository = new FakePaymentRepository();
        gateway = new FakePaymentGateway();
        productListCache = new FakeProductListCache();
        productDetailCache = new FakeProductDetailCache();
        clock = new MutableClock(CREATED_AT);
        OrderService orderService = new OrderService(orderRepository, clock);
        ProductService productService = new ProductService(productRepository, new FakeProductLikeCountRepository(), clock);
        PaymentService paymentService = new PaymentService(paymentRepository, clock);
        OrderPaymentSteps steps = new OrderPaymentSteps(orderService, productService, paymentService);
        orderFacade = new OrderFacade(orderService, productService, paymentService);
        orderPaymentFacade = new OrderPaymentFacade(steps, gateway, productListCache, productDetailCache);
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

    private Order orderOf(Long orderId) {
        return orderRepository.findById(orderId).orElseThrow();
    }

    private Payment paymentOf(Order order) {
        return paymentRepository.findByOrderId(order.getId()).orElseThrow();
    }

    private static OrderRequestLine line(Product product, int quantity) {
        return new OrderRequestLine(product.getId(), quantity);
    }

    private static ConfirmCommand command(String attemptKey) {
        return new ConfirmCommand(attemptKey, "TEST", "0000-0000-0000-0000");
    }

    private OrderInfo confirm(Long userId, Order order, String attemptKey) {
        return orderPaymentFacade.confirm(userId, order.getId(), command(attemptKey));
    }

    private static Long failedProductId(CoreException exception) {
        return ((ProductErrorDetail) exception.getDetail()).productId();
    }

    private static PaymentLookupResult foundOne(Order order, ProviderTransactionStatus status) {
        return new PaymentLookupResult.Found(List.of(
            new ProviderTransaction("TEST-TX-0001", Payment.providerOrderIdOf(order.getId()), order.getTotalAmount(), status)));
    }

    private void assertNotStarted(Order order, Product first, int firstStock, Product second, int secondStock) {
        assertAll(
            () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
            () -> assertThat(order.getPaymentMethod()).isNull(),
            () -> assertThat(first.getStock()).isEqualTo(firstStock),
            () -> assertThat(second.getStock()).isEqualTo(secondStock),
            () -> assertThat(paymentRepository.count()).isZero(),
            () -> assertThat(gateway.createRequests()).isEmpty()
        );
    }

    @DisplayName("주문을 확정할 때, ")
    @Nested
    class Confirm {
        @DisplayName("PG 가 생성을 수락하면, 재고를 확보하고 PAYMENT_PENDING · 결제 PENDING 이 되며 결제 정보는 아직 비어 있다.")
        @Test
        void reservesStockAndStartsPayment_whenAccepted() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(2_500L, 5);
            Order order = createOrder(line(first, 2), line(second, 2));

            // act
            OrderInfo result = confirm(USER_ID, order, "attempt-1");

            // assert
            Payment payment = paymentOf(order);
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(result.paymentStatus()).isEqualTo("PENDING"),
                () -> assertThat(result.paymentInProgress()).isTrue(),
                () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG),
                () -> assertThat(order.getPaymentAmount()).isNull(),
                () -> assertThat(order.getPaidAt()).isNull(),
                () -> assertThat(first.getStock()).isEqualTo(8),
                () -> assertThat(second.getStock()).isEqualTo(3),
                () -> assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING),
                () -> assertThat(payment.getReservationStatus()).isEqualTo(ReservationStatus.RESERVED),
                () -> assertThat(payment.getTransactionKey()).isEqualTo("TEST-TX-0001")
            );
        }

        @DisplayName("PG 에는 providerOrderId · 저장된 주문 합계 · 받은 카드 값으로 생성을 한 번 보낸다.")
        @Test
        void sendsCreateOnce_withStoredAmount() {
            // arrange
            Order order = createOrder(line(saveProduct(1_000L, 10), 7));

            // act
            confirm(USER_ID, order, "attempt-1");

            // assert
            assertThat(gateway.createRequests()).containsExactly(
                new PaymentGatewayRequest(Payment.providerOrderIdOf(order.getId()), "TEST", "0000-0000-0000-0000", 7_000L));
        }

        @DisplayName("PG 가 효과 0건으로 확정 거절하면, 결제 REJECTED · 주문 PAYMENT_FAILED 이고 확보한 재고를 한 번 반환한다.")
        @Test
        void releasesStock_whenRejectedWithoutEffect() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = createOrder(line(product, 2));
            gateway.willCreate(new PaymentCreateResult.RejectedWithoutEffect());

            // act
            OrderInfo result = confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(result.paymentStatus()).isEqualTo("REJECTED"),
                () -> assertThat(result.paymentInProgress()).isFalse(),
                () -> assertThat(product.getStock()).isEqualTo(5),
                () -> assertThat(paymentOf(order).getReservationStatus()).isEqualTo(ReservationStatus.RELEASED),
                () -> assertThat(paymentOf(order).getTransactionKey()).isNull()
            );
        }

        @DisplayName("생성 결과를 확인하지 못하면, 결제 UNKNOWN · PAYMENT_PENDING 이고 확보한 재고를 유지한다. (timeout 은 실패가 아님)")
        @Test
        void keepsReservation_whenResultIsUnknown() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = createOrder(line(product, 2));
            gateway.willCreate(new PaymentCreateResult.Unknown());

            // act
            OrderInfo result = confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(result.paymentStatus()).isEqualTo("UNKNOWN"),
                () -> assertThat(result.paymentInProgress()).isTrue(),
                () -> assertThat(product.getStock()).isEqualTo(3),
                () -> assertThat(gateway.createRequests()).hasSize(1)
            );
        }

        @DisplayName("같은 시도 키로 다시 보내면, 저장된 상태만 돌려주고 PG 생성 · 재고 확보를 다시 하지 않는다.")
        @Test
        void returnsStoredState_whenSameAttemptKeyIsRepeated() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = createOrder(line(product, 2));
            gateway.willCreate(new PaymentCreateResult.Unknown());
            confirm(USER_ID, order, "attempt-1");

            // act
            OrderInfo result = confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(result.paymentStatus()).isEqualTo("UNKNOWN"),
                () -> assertThat(gateway.createRequests()).hasSize(1),
                () -> assertThat(gateway.lookups()).isEmpty(),
                () -> assertThat(product.getStock()).isEqualTo(3),
                () -> assertThat(paymentRepository.count()).isEqualTo(1)
            );
        }

        @DisplayName("같은 주문에 다른 시도 키를 보내면, PAYMENT_ATTEMPT_CONFLICT 예외가 발생하고 두 번째 PG 시도를 만들지 않는다.")
        @Test
        void throwsConflict_whenDifferentAttemptKeyForSameOrder() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = createOrder(line(product, 2));
            gateway.willCreate(new PaymentCreateResult.Unknown());
            confirm(USER_ID, order, "attempt-1");

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-2"));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT),
                () -> assertThat(gateway.createRequests()).hasSize(1),
                () -> assertThat(product.getStock()).isEqualTo(3)
            );
        }

        @DisplayName("다른 주문이 이미 쓴 시도 키면, 재고를 확보하기 전에 PAYMENT_ATTEMPT_CONFLICT 예외가 발생한다.")
        @Test
        void throwsConflict_beforeReserving_whenAttemptKeyBelongsToAnotherOrder() {
            // arrange
            Product first = saveProduct(1_000L, 5);
            Product second = saveProduct(1_000L, 5);
            confirm(USER_ID, createOrder(line(first, 1)), "attempt-1");
            Order another = createOrder(line(second, 1));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, another, "attempt-1"));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT),
                () -> assertThat(another.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(second.getStock()).isEqualTo(5),
                () -> assertThat(gateway.createRequests()).hasSize(1),
                () -> assertThat(paymentRepository.count()).isEqualTo(1)
            );
        }

        static Stream<Arguments> invalidCommands() {
            return Stream.of(
                Arguments.of("시도 키 없음", new ConfirmCommand(null, "TEST", "0000-0000-0000-0000")),
                Arguments.of("시도 키 공백", new ConfirmCommand("  ", "TEST", "0000-0000-0000-0000")),
                Arguments.of("시도 키 65자", new ConfirmCommand("k".repeat(65), "TEST", "0000-0000-0000-0000")),
                Arguments.of("카드 종류 없음", new ConfirmCommand("attempt-1", null, "0000-0000-0000-0000")),
                Arguments.of("카드 번호 공백", new ConfirmCommand("attempt-1", "TEST", " "))
            );
        }

        @DisplayName("시도 키 · 카드 값이 비었거나 너무 길면, 주문을 다루기 전에 INVALID_PAYMENT_REQUEST 예외가 발생한다.")
        @ParameterizedTest(name = "{0}")
        @MethodSource("invalidCommands")
        void throwsInvalidRequest_beforeTouchingOrder(String name, ConfirmCommand command) {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = createOrder(line(product, 1));

            // act
            CoreException result = assertThrows(CoreException.class, () -> orderPaymentFacade.confirm(USER_ID, order.getId(), command));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.INVALID_PAYMENT_REQUEST),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(product.getStock()).isEqualTo(5),
                () -> assertThat(gateway.createRequests()).isEmpty()
            );
        }

        @DisplayName("타인의 주문이면, ORDER_NOT_FOUND 예외가 발생한다. (ORD-06)")
        @Test
        void throwsOrderNotFound_whenOrderBelongsToOthers() {
            Order order = createOrder(line(saveProduct(1_000L, 10), 1));

            CoreException result = assertThrows(CoreException.class, () -> confirm(OTHER_USER_ID, order, "attempt-1"));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND),
                () -> assertThat(gateway.createRequests()).isEmpty()
            );
        }

        @DisplayName("5주차 전 포인트로 확정한 주문이면, 기존 ORDER_ALREADY_CONFIRMED 예외가 발생하고 PG 를 부르지 않는다. (ORD-07)")
        @Test
        void throwsAlreadyConfirmed_forPastPointOrder() {
            // arrange
            Order order = createOrder(line(saveProduct(1_000L, 10), 1));
            order.confirmWithPoint(order.getTotalAmount(), CREATED_AT.plusMinutes(1));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-1"));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_ALREADY_CONFIRMED),
                () -> assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.POINT),
                () -> assertThat(gateway.createRequests()).isEmpty()
            );
        }

        @DisplayName("상품이 삭제되었으면, 그 상품을 알리는 PRODUCT_NOT_FOUND 예외가 발생하고 결제를 시작하지 않는다. (ORD-09)")
        @Test
        void throwsProductNotFound_whenProductIsDeleted() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 10);
            Order order = createOrder(line(first, 1), line(second, 1));
            second.delete();

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-1"));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.PRODUCT_NOT_FOUND);
            assertThat(failedProductId(result)).isEqualTo(second.getId());
            assertNotStarted(order, first, 10, second, 10);
        }

        @DisplayName("생성 후 가격이 오르거나 내려도, 주문서 합계로 결제를 요청한다. (설계 2.3)")
        @ParameterizedTest
        @ValueSource(longs = {1_500L, 800L})
        void requestsOrderTotal_whenPriceChanged(long changedPrice) {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 10);
            Order order = createOrder(line(first, 1), line(second, 2));
            second.update("상품", changedPrice);

            // act
            confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(gateway.createRequests()).extracting(PaymentGatewayRequest::amount).containsExactly(3_000L),
                () -> assertThat(paymentOf(order).getAmount()).isEqualTo(3_000L)
            );
        }

        @DisplayName("두 번째 품목의 재고가 부족하면, 그 상품을 알리는 OUT_OF_STOCK 예외가 발생하고 PG 를 부르지 않는다. (ORD-10)")
        @Test
        void throwsOutOfStock_withFailedProduct_withoutCallingPg() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 1);
            Order order = createOrder(line(first, 2), line(second, 2));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-1"));

            // assert
            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(ProductErrorCode.OUT_OF_STOCK),
                () -> assertThat(failedProductId(result)).isEqualTo(second.getId()),
                () -> assertThat(order.getStatus()).isEqualTo(OrderStatus.DRAFT),
                () -> assertThat(paymentRepository.count()).isZero(),
                () -> assertThat(gateway.createRequests()).isEmpty()
            );
        }

        @DisplayName("생성 후 30분이 지났으면, ORDER_EXPIRED 예외가 발생하고 결제를 시작하지 않는다. (ORD-08)")
        @Test
        void throwsExpired_whenThirtyMinutesPassed() {
            // arrange
            Product first = saveProduct(1_000L, 10);
            Product second = saveProduct(1_000L, 10);
            Order order = createOrder(line(first, 1), line(second, 1));
            clock.advance(Duration.ofMinutes(30));

            // act
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-1"));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_EXPIRED);
            assertNotStarted(order, first, 10, second, 10);
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
            CoreException result = assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-1"));

            // assert
            assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_EXPIRED);
        }

        @DisplayName("만료 1초 전이면, 결제를 시작한다. 결제를 시작한 뒤에는 만료로 끝내지 않는다. (ORD-08, 5주차 설계 2.1)")
        @Test
        void startsPayment_justBeforeExpiry() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            Order order = createOrder(line(product, 1));
            clock.advance(Duration.ofMinutes(30).minusSeconds(1));

            // act
            OrderInfo result = confirm(USER_ID, order, "attempt-1");

            // assert
            assertThat(result.status()).isEqualTo("PAYMENT_PENDING");
        }
    }

    @DisplayName("0원 주문을 확정할 때, (D-68)")
    @Nested
    class ZeroAmount {
        @DisplayName("PG 를 부르지 않고 재고를 확보해 바로 CONFIRMED · 결제수단 NONE · 결제액 0 이 된다.")
        @Test
        void confirmsWithoutCallingPg() {
            // arrange
            Product free = saveProduct(0L, 5);
            Order order = createOrder(line(free, 2));

            // act
            OrderInfo result = confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(result.paymentMethod()).isEqualTo("NONE"),
                () -> assertThat(result.paymentStatus()).isEqualTo("NOT_REQUIRED"),
                () -> assertThat(result.paymentInProgress()).isFalse(),
                () -> assertThat(order.getPaymentAmount()).isZero(),
                () -> assertThat(order.getPaidAt()).isEqualTo(CREATED_AT),
                () -> assertThat(free.getStock()).isEqualTo(3),
                () -> assertThat(paymentOf(order).getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED),
                () -> assertThat(gateway.createRequests()).isEmpty()
            );
        }

        @DisplayName("같은 시도 키로 다시 보내면 저장된 상태를 돌려주고, 다른 키면 PAYMENT_ATTEMPT_CONFLICT 예외가 발생한다. 재고는 한 번만 확보한다.")
        @Test
        void keepsAttemptKeyContract() {
            // arrange
            Product free = saveProduct(0L, 5);
            Order order = createOrder(line(free, 2));
            confirm(USER_ID, order, "attempt-1");

            // act
            OrderInfo repeated = confirm(USER_ID, order, "attempt-1");
            CoreException conflict = assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-2"));

            // assert
            assertAll(
                () -> assertThat(repeated.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(repeated.paymentStatus()).isEqualTo("NOT_REQUIRED"),
                () -> assertThat(conflict.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_ATTEMPT_CONFLICT),
                () -> assertThat(free.getStock()).isEqualTo(3),
                () -> assertThat(paymentRepository.count()).isEqualTo(1)
            );
        }

        @DisplayName("결과를 확인해도 PG 를 조회하지 않고 저장된 상태를 돌려준다.")
        @Test
        void reconcileDoesNotLookUp() {
            // arrange
            Order order = createOrder(line(saveProduct(0L, 5), 1));
            confirm(USER_ID, order, "attempt-1");

            // act
            OrderInfo result = orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(result.paymentStatus()).isEqualTo("NOT_REQUIRED"),
                () -> assertThat(gateway.lookups()).isEmpty()
            );
        }
    }

    @DisplayName("결제 결과를 확인할 때, ")
    @Nested
    class Reconcile {
        private Order pendingOrder(Product product, int quantity) {
            Order order = createOrder(line(product, quantity));
            gateway.willCreate(new PaymentCreateResult.Unknown());
            confirm(USER_ID, order, "attempt-1");
            return order;
        }

        @DisplayName("조회로 SUCCESS 를 확인하면, CONFIRMED 와 결제액 · 반영 시각을 남기고 재고를 더 차감하지 않는다.")
        @Test
        void confirms_withoutDeductingAgain_whenSucceeded() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(foundOne(order, ProviderTransactionStatus.SUCCESS));
            clock.advance(Duration.ofHours(1));

            // act
            OrderInfo result = orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(result.paymentStatus()).isEqualTo("SUCCESS"),
                () -> assertThat(result.paymentInProgress()).isFalse(),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(2_000L),
                () -> assertThat(order.getPaidAt()).isEqualTo(CREATED_AT.plusHours(1)),
                () -> assertThat(product.getStock()).isEqualTo(3),
                () -> assertThat(paymentOf(order).getReservationStatus()).isEqualTo(ReservationStatus.CONSUMED),
                () -> assertThat(gateway.lookups()).containsExactly(Payment.providerOrderIdOf(order.getId())),
                () -> assertThat(gateway.createRequests()).hasSize(1)
            );
        }

        @DisplayName("조회로 FAILED 를 확인하면, PAYMENT_FAILED 가 되고 재고를 한 번 반환한다. 다시 확인해도 PG 를 조회하지 않고 반환이 늘지 않는다.")
        @Test
        void releasesOnce_whenFailed() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(foundOne(order, ProviderTransactionStatus.FAILED));

            // act
            OrderInfo first = orderPaymentFacade.reconcile(USER_ID, order.getId());
            OrderInfo second = orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(first.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(second.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(product.getStock()).isEqualTo(5),
                () -> assertThat(gateway.lookups()).hasSize(1)
            );
        }

        @DisplayName("거래가 아직 처리 중이면, 거래 키를 저장하고 PENDING 으로 올리되 재고는 그대로 둔다.")
        @Test
        void becomesPending_whenTransactionIsPending() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(foundOne(order, ProviderTransactionStatus.PENDING));

            // act
            OrderInfo result = orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(result.paymentStatus()).isEqualTo("PENDING"),
                () -> assertThat(paymentOf(order).getTransactionKey()).isEqualTo("TEST-TX-0001"),
                () -> assertThat(product.getStock()).isEqualTo(3)
            );
        }

        @DisplayName("PG 를 조회하지 못하면, 저장된 상태를 그대로 두고 확인 불가를 알린다. 실패로 확정하거나 재고를 반환하지 않는다.")
        @Test
        void keepsStoredState_whenLookupIsUnavailable() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(new PaymentLookupResult.Unavailable());

            // act
            OrderInfo result = orderPaymentFacade.reconcile(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(result.paymentStatus()).isEqualTo("UNKNOWN"),
                () -> assertThat(result.paymentUnavailable()).isTrue(),
                () -> assertThat(product.getStock()).isEqualTo(3),
                () -> assertThat(gateway.createRequests()).hasSize(1)
            );
        }

        @DisplayName("결제를 시작하지 않은 주문이면, PAYMENT_NOT_FOUND 예외가 발생한다.")
        @Test
        void throwsPaymentNotFound_whenNoAttempt() {
            Order order = createOrder(line(saveProduct(1_000L, 5), 1));

            CoreException result = assertThrows(CoreException.class, () -> orderPaymentFacade.reconcile(USER_ID, order.getId()));

            assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND);
        }

        @DisplayName("타인의 주문이면, ORDER_NOT_FOUND 예외가 발생하고 PG 를 조회하지 않는다.")
        @Test
        void throwsOrderNotFound_whenOrderBelongsToOthers() {
            Order order = pendingOrder(saveProduct(1_000L, 5), 1);

            CoreException result = assertThrows(CoreException.class, () -> orderPaymentFacade.reconcile(OTHER_USER_ID, order.getId()));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(OrderErrorCode.ORDER_NOT_FOUND),
                () -> assertThat(gateway.lookups()).isEmpty()
            );
        }
    }

    @DisplayName("PG 콜백을 받을 때, ")
    @Nested
    class Callback {
        private Order pendingOrder(Product product, int quantity) {
            Order order = createOrder(line(product, quantity));
            gateway.willCreate(new PaymentCreateResult.Unknown());
            confirm(USER_ID, order, "attempt-1");
            return order;
        }

        @DisplayName("조회로 SUCCESS 를 확인하면, 본인 확인 없이 CONFIRMED 로 반영하고 재고를 더 차감하지 않는다.")
        @Test
        void confirms_whenLookupSucceeded() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(foundOne(order, ProviderTransactionStatus.SUCCESS));

            // act
            OrderInfo result = orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("CONFIRMED"),
                () -> assertThat(order.getPaymentAmount()).isEqualTo(2_000L),
                () -> assertThat(product.getStock()).isEqualTo(3),
                () -> assertThat(gateway.lookups()).containsExactly(Payment.providerOrderIdOf(order.getId()))
            );
        }

        @DisplayName("조회 결과가 아직 처리 중이면, 본문이 SUCCESS 라고 해도 확정하지 않는다.")
        @Test
        void doesNotConfirm_whenLookupIsStillPending() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(foundOne(order, ProviderTransactionStatus.PENDING));

            // act
            OrderInfo result = orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(result.paymentStatus()).isEqualTo("PENDING"),
                () -> assertThat(order.getPaymentAmount()).isNull(),
                () -> assertThat(product.getStock()).isEqualTo(3)
            );
        }

        @DisplayName("이미 종결된 결제면, PG 를 조회하지 않고 저장된 상태를 돌려준다. 같은 콜백이 다시 와도 반환이 늘지 않는다.")
        @Test
        void skipsLookup_whenAlreadySettled() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(foundOne(order, ProviderTransactionStatus.FAILED));
            orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));

            // act
            OrderInfo result = orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(product.getStock()).isEqualTo(5),
                () -> assertThat(gateway.lookups()).hasSize(1)
            );
        }

        @DisplayName("PG 를 조회하지 못하면, 저장된 상태를 그대로 둔다.")
        @Test
        void keepsStoredState_whenLookupIsUnavailable() {
            // arrange
            Product product = saveProduct(1_000L, 5);
            Order order = pendingOrder(product, 2);
            gateway.willLookup(new PaymentLookupResult.Unavailable());

            // act
            OrderInfo result = orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(order.getId()));

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_PENDING"),
                () -> assertThat(result.paymentStatus()).isEqualTo("UNKNOWN"),
                () -> assertThat(result.paymentUnavailable()).isTrue(),
                () -> assertThat(product.getStock()).isEqualTo(3)
            );
        }

        @DisplayName("모르는 주문 식별자면, PAYMENT_NOT_FOUND 예외가 발생하고 PG 를 조회하지 않는다.")
        @Test
        void throwsPaymentNotFound_whenProviderOrderIdIsUnknown() {
            CoreException result = assertThrows(CoreException.class,
                () -> orderPaymentFacade.handleCallback(Payment.providerOrderIdOf(999L)));

            assertAll(
                () -> assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.PAYMENT_NOT_FOUND),
                () -> assertThat(gateway.lookups()).isEmpty()
            );
        }

        @DisplayName("주문 식별자가 비어 있으면, INVALID_PAYMENT_REQUEST 예외가 발생한다.")
        @ParameterizedTest
        @ValueSource(strings = {"", " "})
        void throwsInvalidRequest_whenProviderOrderIdIsBlank(String providerOrderId) {
            CoreException result = assertThrows(CoreException.class, () -> orderPaymentFacade.handleCallback(providerOrderId));

            assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }

        @DisplayName("주문 식별자가 없으면, INVALID_PAYMENT_REQUEST 예외가 발생한다.")
        @Test
        void throwsInvalidRequest_whenProviderOrderIdIsNull() {
            CoreException result = assertThrows(CoreException.class, () -> orderPaymentFacade.handleCallback(null));

            assertThat(result.getErrorCode()).isEqualTo(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }

    @DisplayName("주문을 조회할 때, (5주차 설계 7장)")
    @Nested
    class Query {
        @DisplayName("목록은 한 페이지의 결제를 한 번에 조회해 결제수단 · 결제 상태를 붙이고, 결제 시도가 없는 주문은 비워 둔다.")
        @Test
        void attachesPaymentToList_withOneBulkLookup() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            Order pending = createOrder(line(product, 1));
            confirm(USER_ID, pending, "attempt-1");
            Order draft = createOrder(line(product, 1));

            // act
            List<OrderSummaryInfo> result = orderFacade.getMyOrders(USER_ID, null, PageRequest.of(0, 20)).getContent();

            // assert
            OrderSummaryInfo pendingRow = result.stream().filter(row -> row.id().equals(pending.getId())).findFirst().orElseThrow();
            OrderSummaryInfo draftRow = result.stream().filter(row -> row.id().equals(draft.getId())).findFirst().orElseThrow();
            assertAll(
                () -> assertThat(pendingRow.paymentMethod()).isEqualTo("PG"),
                () -> assertThat(pendingRow.paymentStatus()).isEqualTo("PENDING"),
                () -> assertThat(draftRow.paymentMethod()).isNull(),
                () -> assertThat(draftRow.paymentStatus()).isNull(),
                () -> assertThat(paymentRepository.findAllByOrderIdsCalls()).isEqualTo(1)
            );
        }

        @DisplayName("상세는 결제수단 · 결제 상태를 담는다.")
        @Test
        void attachesPaymentToDetail() {
            // arrange
            Product product = saveProduct(1_000L, 10);
            Order order = createOrder(line(product, 1));
            gateway.willCreate(new PaymentCreateResult.RejectedWithoutEffect());
            confirm(USER_ID, order, "attempt-1");

            // act
            OrderDetailInfo result = orderFacade.getMyOrder(USER_ID, order.getId());

            // assert
            assertAll(
                () -> assertThat(result.status()).isEqualTo("PAYMENT_FAILED"),
                () -> assertThat(result.paymentMethod()).isEqualTo("PG"),
                () -> assertThat(result.paymentStatus()).isEqualTo("REJECTED")
            );
        }
    }

    @DisplayName("목록 · 상세 캐시는, (4주차 cache-design.md 3.3, detail-cache-design.md 3.2)")
    @Nested
    class CacheInvalidation {
        @DisplayName("재고 확보로 품절이 된 상품이 있으면, 그 상품의 브랜드 목록과 그 상품의 상세만 무효화한다.")
        @Test
        void invalidates_whenReservationMakesSoldOut() {
            // arrange
            Product soldOutLater = saveProduct(1_000L, 2);
            Product remaining = saveProduct(1_000L, 10);
            Order order = createOrder(line(soldOutLater, 2), line(remaining, 1));

            // act
            confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(productListCache.invalidatedBrandIds()).containsExactly(brand.getId()),
                () -> assertThat(productDetailCache.invalidatedProductIds()).containsExactly(soldOutLater.getId())
            );
        }

        @DisplayName("재고가 남으면 품절 여부가 바뀌지 않아, 무효화하지 않는다.")
        @Test
        void doesNotInvalidate_whenNoProductBecomesSoldOut() {
            // arrange
            Order order = createOrder(line(saveProduct(1_000L, 10), 9));

            // act
            confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(productListCache.invalidatedBrandIds()).isEmpty(),
                () -> assertThat(productDetailCache.invalidatedProductIds()).isEmpty()
            );
        }

        @DisplayName("결제 시작이 실패하면, 품절이 될 뻔한 상품이 있어도 무효화하지 않는다.")
        @Test
        void doesNotInvalidate_whenStartFails() {
            // arrange
            Product soldOutLater = saveProduct(1_000L, 1);
            Product shortage = saveProduct(1_000L, 0);
            Order order = createOrder(line(soldOutLater, 1), line(shortage, 1));

            // act
            assertThrows(CoreException.class, () -> confirm(USER_ID, order, "attempt-1"));

            // assert
            assertAll(
                () -> assertThat(productListCache.invalidatedBrandIds()).isEmpty(),
                () -> assertThat(productDetailCache.invalidatedProductIds()).isEmpty()
            );
        }

        @DisplayName("확정 실패로 반환해 품절이 풀리면, 확보 때와 반환 때 한 번씩 무효화한다.")
        @Test
        void invalidatesAgain_whenReleaseLiftsSoldOut() {
            // arrange
            Product product = saveProduct(1_000L, 2);
            Order order = createOrder(line(product, 2));
            gateway.willCreate(new PaymentCreateResult.RejectedWithoutEffect());

            // act
            confirm(USER_ID, order, "attempt-1");

            // assert
            assertAll(
                () -> assertThat(product.getStock()).isEqualTo(2),
                () -> assertThat(productListCache.invalidatedBrandIds()).containsExactly(brand.getId(), brand.getId()),
                () -> assertThat(productDetailCache.invalidatedProductIds()).containsExactly(product.getId(), product.getId())
            );
        }
    }
}
