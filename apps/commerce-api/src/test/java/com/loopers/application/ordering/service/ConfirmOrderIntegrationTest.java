package com.loopers.application.ordering.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;

import com.loopers.application.mall.command.BrandCommand;
import com.loopers.application.mall.usecase.DeleteBrandUseCase;
import com.loopers.support.test.PgPaymentTestSupport;
import com.loopers.application.ordering.result.ConfirmOrderResult;
import com.loopers.application.ordering.usecase.ConfirmOrderUseCase;
import com.loopers.application.pay.gateway.PaymentProviderGateway;
import com.loopers.application.pay.gateway.PaymentProviderGateway.ExpectedPayment;
import com.loopers.domain.pay.model.PaymentObservation;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.mall.model.Brand;
import com.loopers.domain.mall.model.Product;
import com.loopers.domain.mall.repository.BrandRepository;
import com.loopers.domain.mall.repository.ProductRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderItem;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.Wallet;
import com.loopers.domain.pay.repository.WalletRepository;
import com.loopers.domain.shared.Money;
import com.loopers.domain.shopping.model.User;
import com.loopers.domain.shopping.repository.UserRepository;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;
import com.loopers.support.test.IntegrationTest;
import com.loopers.support.test.OrderConfirmProbe;
import com.loopers.utils.DatabaseCleanUp;
import java.util.List;
import org.assertj.core.groups.Tuple;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@IntegrationTest
class ConfirmOrderIntegrationTest {
    @Autowired private PaymentProviderGateway gateway;

    @Test
    @DisplayName("생성 PENDING은 202 대상 상태로 남고 같은 요청은 PG 생성과 재고 차감을 반복하지 않는다")
    void leavesPendingWithoutRedispatch() {
        long productId = createProduct(5);
        Order order = createOrder(1L, productId, 2, 1_000L);
        ConfirmOrderResult pending = confirmOrderUseCase.execute(PgPaymentTestSupport.command(order));
        assertThat(pending.completed()).isFalse();
        assertThat(pending.order().status()).isEqualTo(OrderStatus.PAYMENT_PENDING);
        assertThat(pending.paymentStatus()).isNull();
        assertThat(pending.paymentAttempt().outcome()).isEqualTo(PaymentAttempt.Outcome.PENDING);
        confirmOrderUseCase.execute(PgPaymentTestSupport.command(order));
        verify(gateway, times(1)).create(any(), any(), any());
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(3);
        assertThat(probe.countUsePointBills(1L, order.getId())).isZero();
    }

    @Test
    @DisplayName("생성 응답 유실은 실패로 확정하거나 다시 생성하지 않고 차감 재고를 유지한다")
    void preservesUnknownAfterLostCreateResponse() {
        doReturn(PaymentObservation.unavailable(PaymentObservation.Reason.TIMEOUT)).when(gateway).create(any(), any(), any());
        long productId = createProduct(5);
        Order order = createOrder(1L, productId, 2, 1_000L);
        ConfirmOrderResult result = confirmOrderUseCase.execute(PgPaymentTestSupport.command(order));
        assertThat(result.completed()).isFalse();
        assertThat(result.paymentAttempt().outcome()).isEqualTo(PaymentAttempt.Outcome.UNKNOWN);
        confirmOrderUseCase.execute(PgPaymentTestSupport.command(order));
        verify(gateway, times(1)).create(any(), any(), any());
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(3);
        assertThat(probe.countPaidOrderRecords(order.getId())).isZero();
    }

    @Test
    @DisplayName("생성 전 확정 거절은 공통 반영으로 실패·재고 복구를 마치고 완료 상태를 반환한다")
    void completesDefinitiveRejection() {
        doAnswer(invocation -> {
            ExpectedPayment expected = invocation.getArgument(0);
            return PaymentObservation.rejected(expected.providerOrderId(), expected.amount());
        }).when(gateway).create(any(), any(), any());
        long productId = createProduct(5);
        Order order = createOrder(1L, productId, 2, 1_000L);
        ConfirmOrderResult result = confirmOrderUseCase.execute(PgPaymentTestSupport.command(order));
        assertThat(result.completed()).isTrue();
        assertThat(result.order().status()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(result.paymentAttempt().rejection()).isEqualTo(PaymentAttempt.Rejection.NO_EFFECT);
        assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(5);
        assertThat(probe.countUsePointBills(1L, order.getId())).isZero();
    }

    @Test
    @DisplayName("과거 POINT 주문의 본인 재확정은 거절하고 타인 요청에는 같은 404를 적용한다")
    void preservesLegacyPointAndChecksOwnerFirst() {
        Order legacy = Order.create(1L, List.of(OrderItem.create(1L, "과거 상품", 1_000L, 2)));
        legacy.confirm();
        Order saved = orderRepository.insert(legacy);
        assertThatThrownBy(() -> confirmOrderUseCase.execute(PgPaymentTestSupport.command(saved)))
            .isInstanceOf(DomainException.class).extracting("errorCode").isEqualTo(DomainErrorCode.ORDER_ALREADY_CONFIRMED);
        assertThatThrownBy(() -> confirmOrderUseCase.execute(PgPaymentTestSupport.command(2L, saved.getId())))
            .isInstanceOf(com.loopers.application.support.error.ApplicationException.class)
            .extracting("errorCode").isEqualTo(com.loopers.application.support.error.ApplicationErrorCode.ORDER_NOT_FOUND);
        verify(gateway, never()).create(any(), any(), any());
        assertThat(orderRepository.findById(saved.getId()).orElseThrow().getRecord().orElseThrow().getPaymentMethod())
            .isEqualTo(com.loopers.domain.ordering.model.PaymentMethod.POINT);
    }
    @Autowired private PgPaymentTestSupport pg;
    @BeforeEach void stubPg() { pg.usePendingProvider(); }
    @Autowired
    private ConfirmOrderUseCase confirmOrderUseCase;
    @Autowired
    private DeleteBrandUseCase deleteBrandUseCase;
    @Autowired
    private BrandRepository brandRepository;
    @Autowired
    private ProductRepository productRepository;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private WalletRepository walletRepository;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private OrderConfirmProbe probe;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("주문 확정")
    @Nested
    class Confirm {
        @DisplayName("재고만 차감하고 PG 완료 반영 뒤 PAID 기록을 남기며 포인트는 보존한다")
        @Test
        void confirmsOrder_withStockPointAndRecords() {
            long productId = createProduct(5);
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            Order order = createOrder(1L, productId, 2, 1_000L);

            ConfirmOrderResult result = pg.confirmAndComplete(order);

            Product reloadedProduct = productRepository.findById(productId).orElseThrow();
            Order reloadedOrder = orderRepository.findById(order.getId()).orElseThrow();
            assertAll(
                () -> assertThat(result.order().status()).isEqualTo(OrderStatus.CONFIRMED),
                () -> assertThat(result.paymentAmount()).isEqualTo(2_000L),
                () -> assertThat(reloadedProduct.getStock()).isEqualTo(3),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isZero(),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isEqualTo(1L),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("CONFIRMED"),
                // 재고 외 무관한 상품 값은 잠금 조회·저장 이후에도 그대로 보존돼야 한다
                () -> assertThat(reloadedProduct.getName()).isEqualTo("상품"),
                () -> assertThat(reloadedProduct.getPrice()).isEqualTo(1_000L),
                () -> assertThat(reloadedProduct.isDeleted()).isFalse(),
                // 주문 당시 품목 스냅샷도 그대로 보존돼야 한다
                () -> assertThat(reloadedOrder.getItems())
                    .extracting(OrderItem::getProductId, OrderItem::getProductName, OrderItem::getUnitPrice,
                        OrderItem::getQuantity)
                    .containsExactly(Tuple.tuple(productId, "상품", 1_000L, 2))
            );
        }

        @DisplayName("두 번째 품목의 재고가 부족하면 전체를 롤백한다")
        @Test
        void rollsBackEverything_whenSecondItemStockIsInsufficient() {
            long sufficientProductId = createProduct(5);
            long insufficientProductId = createProduct(1);
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            List<OrderItem> items = List.of(
                OrderItem.create(sufficientProductId, "상품1", 1_000L, 2),
                OrderItem.create(insufficientProductId, "상품2", 1_000L, 2)
            );
            Order order = orderRepository.insert(Order.create(1L, items));

            assertThatThrownBy(() -> pg.confirmAndComplete(order))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.INSUFFICIENT_STOCK);

            assertAll(
                () -> assertThat(productRepository.findById(sufficientProductId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(productRepository.findById(insufficientProductId).orElseThrow().getStock()).isEqualTo(1),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("DRAFT"),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isZero(),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isZero()
            );
        }

        @DisplayName("포인트가 없어도 PG 결제를 완료하고 포인트는 차감하지 않는다")
        @Test
        void rollsBackEverything_whenPointIsInsufficient() {
            long productId = createProduct(5);
            userRepository.save(User.create(1L));
            walletRepository.save(Wallet.zero(1L));
            Order order = createOrder(1L, productId, 2, 1_000L);

            assertThat(pg.confirmAndComplete(order).completed()).isTrue();

            assertAll(
                () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(3),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isZero(),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("CONFIRMED"),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isEqualTo(1L)
            );
        }

        @DisplayName("브랜드 일괄 삭제로 상품이 삭제되면 DRAFT 확정을 거절하고 재고·잔액·기록을 보존한다")
        @Test
        void rejectsConfirm_whenProductDeletedViaBrandBulkDelete() {
            Brand brand = brandRepository.save(Brand.create("브랜드", null));
            long productId = productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, 5)).getId();
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            Order order = createOrder(1L, productId, 2, 1_000L);

            deleteBrandUseCase.execute(new BrandCommand.Delete(brand.getId()));

            assertThatThrownBy(() -> pg.confirmAndComplete(order))
                .isInstanceOf(DomainException.class)
                .extracting("errorCode")
                .isEqualTo(DomainErrorCode.DELETED_PRODUCT);

            assertAll(
                () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(5),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L),
                () -> assertThat(probe.orderStatus(order.getId())).isEqualTo("DRAFT"),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isZero(),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isZero()
            );
        }

        @DisplayName("같은 PG 요청을 다시 확정하면 추가 차감·생성·기록 없이 완료 상태를 반환한다")
        @Test
        void rejectsReconfirm_withoutAdditionalChangesOrRecords() {
            long productId = createProduct(5);
            userRepository.save(User.create(1L));
            Wallet wallet = walletRepository.save(Wallet.zero(1L));
            wallet.charge(Money.positive(10_000L));
            walletRepository.save(wallet);
            Order order = createOrder(1L, productId, 2, 1_000L);
            pg.confirmAndComplete(order);

            assertThat(pg.confirmAndComplete(order).completed()).isTrue();

            assertAll(
                () -> assertThat(productRepository.findById(productId).orElseThrow().getStock()).isEqualTo(3),
                () -> assertThat(walletRepository.findByUserId(1L).orElseThrow().getBalance()).isEqualTo(10_000L),
                () -> assertThat(probe.countUsePointBills(1L, order.getId())).isZero(),
                () -> assertThat(probe.countPaidOrderRecords(order.getId())).isEqualTo(1L)
            );
        }
    }

    private long createProduct(int stock) {
        Brand brand = brandRepository.save(Brand.create("브랜드", null));
        return productRepository.save(Product.create(brand.getId(), "상품", null, 1_000L, stock)).getId();
    }

    private Order createOrder(long userId, long productId, int quantity, long unitPrice) {
        OrderItem item = OrderItem.create(productId, "상품", unitPrice, quantity);
        return orderRepository.insert(Order.create(userId, List.of(item)));
    }
}
