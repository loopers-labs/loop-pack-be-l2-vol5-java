package com.loopers.application.payment;

import com.loopers.application.brand.BrandFacade;
import com.loopers.application.order.OrderCreateCommand;
import com.loopers.application.order.OrderFacade;
import com.loopers.application.product.ProductFacade;
import com.loopers.domain.common.Money;
import com.loopers.domain.common.Quantity;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderQuantity;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.CardType;
import com.loopers.domain.payment.Payment;
import com.loopers.domain.payment.PaymentAttemptKey;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.DomainError;
import com.loopers.support.error.DomainException;
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
class PaymentTransactionsIntegrationTest {

    private static final Long USER = 1L;
    private static final Long OTHER_USER = 2L;
    private static final Instant NOW = Instant.parse("2026-10-23T00:00:00Z");
    private static final PaymentAttemptKey KEY = PaymentAttemptKey.of("attempt-1");

    private final PaymentTransactions paymentTransactions;
    private final PaymentRepository paymentRepository;
    private final OrderFacade orderFacade;
    private final ProductService productService;
    private final BrandFacade brandFacade;
    private final ProductFacade productFacade;
    private final DatabaseCleanUp databaseCleanUp;

    private Long brandId;
    private Long coat;
    private Long shirt;

    @Autowired
    PaymentTransactionsIntegrationTest(
        PaymentTransactions paymentTransactions,
        PaymentRepository paymentRepository,
        OrderFacade orderFacade,
        ProductService productService,
        BrandFacade brandFacade,
        ProductFacade productFacade,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentTransactions = paymentTransactions;
        this.paymentRepository = paymentRepository;
        this.orderFacade = orderFacade;
        this.productService = productService;
        this.brandFacade = brandFacade;
        this.productFacade = productFacade;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        brandId = brandFacade.register("무신사", "패션 플랫폼").getId();
        coat = productFacade.register(brandId, "코트", Price.of(3_000)).getId();
        shirt = productFacade.register(brandId, "셔츠", Price.of(1_000)).getId();
        productFacade.adjustStock(coat, Quantity.of(10));
        productFacade.adjustStock(shirt, Quantity.of(1));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order place(Long userId, int coats) {
        return orderFacade.place(new OrderCreateCommand(userId,
            List.of(new OrderCreateCommand.Line(coat, OrderQuantity.of(coats)))), NOW);
    }

    private PaymentBegin begin(Long userId, Long orderId, PaymentAttemptKey key) {
        return paymentTransactions.begin(userId, orderId, key, CardType.TEST, NOW);
    }

    @Nested
    @DisplayName("PAY-004, RSV-001: 처음 온 요청이 발송 권한을 얻는다")
    class Granted {

        @DisplayName("DRAFT 주문이면 재고를 차감하고 REQUESTED 시도를 남기고 주문은 PENDING 이 된다")
        @Test
        void grants() {
            Order order = place(USER, 2);

            PaymentBegin result = begin(USER, order.getId(), KEY);

            assertThat(result).isInstanceOf(PaymentBegin.Granted.class);
            Payment payment = result.payment();
            assertThat(payment.getId()).isNotNull();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(payment.getOrderId()).isEqualTo(order.getId());
            assertThat(payment.getAmount()).isEqualTo(Money.of(6_000));
            assertThat(paymentRepository.findByAttemptKey(KEY)).isPresent();
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    @Nested
    @DisplayName("PAY-015, PAY-002: 같은 키가 다시 오면 키를 DRAFT 보다 먼저 본다")
    class SameKey {

        @DisplayName("같은 키, 같은 요청은 저장된 시도를 돌려주고 아무것도 다시 하지 않는다")
        @Test
        void repeats() {
            Order order = place(USER, 2);
            Payment first = begin(USER, order.getId(), KEY).payment();

            PaymentBegin again = begin(USER, order.getId(), KEY);

            assertThat(again).isInstanceOf(PaymentBegin.Repeated.class);
            assertThat(again.payment().getId()).isEqualTo(first.getId());
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }

        @DisplayName("같은 키를 다른 주문에 쓰면 충돌이고, 그 주문은 그대로다")
        @Test
        void conflictsForOtherOrder() {
            Order first = place(USER, 2);
            Order second = place(USER, 1);
            begin(USER, first.getId(), KEY);

            assertThatThrownBy(() -> begin(USER, second.getId(), KEY))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PAYMENT_ATTEMPT_CONFLICT);

            assertThat(orderFacade.get(USER, second.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(paymentRepository.findByOrderId(second.getId())).isEmpty();
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    @Nested
    @DisplayName("PAY-003, PAY-014, Q-1: 시도가 있는 주문에 새 키가 오면 거절한다")
    class NewKey {

        @DisplayName("새 키는 ORDER_NOT_DRAFT 이고 두 번째 시도는 생기지 않는다")
        @Test
        void rejectsNewKeyOnStartedOrder() {
            Order order = place(USER, 2);
            Payment first = begin(USER, order.getId(), KEY).payment();

            assertThatThrownBy(() -> begin(USER, order.getId(), PaymentAttemptKey.of("attempt-2")))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_DRAFT);

            assertThat(paymentRepository.findByOrderId(order.getId()))
                .map(Payment::getId).contains(first.getId());
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(8));
        }
    }

    @Nested
    @DisplayName("ORDER-011: 남의 주문은 없는 주문이다")
    class Ownership {

        @DisplayName("남의 주문이면 ORDER_NOT_FOUND 이고 아무것도 남지 않는다")
        @Test
        void rejectsOthersOrder() {
            Order order = place(OTHER_USER, 2);

            assertThatThrownBy(() -> begin(USER, order.getId(), KEY))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.ORDER_NOT_FOUND);

            assertThat(paymentRepository.findByAttemptKey(KEY)).isEmpty();
            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(10));
        }

        @DisplayName("남의 시도 키를 내 주문에 써도 남의 시도를 돌려주지 않고 충돌이다")
        @Test
        void doesNotRevealOthersAttempt() {
            Order others = place(OTHER_USER, 2);
            Order mine = place(USER, 2);
            begin(OTHER_USER, others.getId(), KEY);

            assertThatThrownBy(() -> begin(USER, mine.getId(), KEY))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PAYMENT_ATTEMPT_CONFLICT);
        }
    }

    @Nested
    @DisplayName("ORDER-012, RSV-001: 접수 뒤 상품이 삭제되면 결제를 시작할 수 없다")
    class DeletedProduct {

        @DisplayName("상품이 삭제되면 PRODUCT_NOT_FOUND 이고, 주문은 DRAFT, 시도는 없다")
        @Test
        void rejectsDeletedProduct() {
            Order order = place(USER, 2);
            productFacade.delete(coat);

            assertThatThrownBy(() -> begin(USER, order.getId(), KEY))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);

            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(paymentRepository.findByAttemptKey(KEY)).isEmpty();
        }

        @DisplayName("브랜드가 삭제되면 그 상품도 삭제되어 같다")
        @Test
        void rejectsDeletedBrand() {
            Order order = place(USER, 2);
            brandFacade.delete(brandId);

            assertThatThrownBy(() -> begin(USER, order.getId(), KEY))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.PRODUCT_NOT_FOUND);

            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(paymentRepository.findByAttemptKey(KEY)).isEmpty();
        }
    }

    @Nested
    @DisplayName("ORDER-013, ORDER-015, RSV-001: 재고가 모자라면 아무것도 남기지 않는다")
    class InsufficientStock {

        @DisplayName("둘째 품목이 모자라면 첫 품목 차감도 되돌리고, 시도도 주문 변화도 없다")
        @Test
        void rollsBackEverything() {
            Order order = orderFacade.place(new OrderCreateCommand(USER, List.of(
                new OrderCreateCommand.Line(coat, OrderQuantity.of(2)),
                new OrderCreateCommand.Line(shirt, OrderQuantity.of(2)))), NOW);

            assertThatThrownBy(() -> begin(USER, order.getId(), KEY))
                .isInstanceOf(DomainException.class)
                .hasFieldOrPropertyWithValue("error", DomainError.INSUFFICIENT_STOCK);

            assertThat(productService.getStock(coat)).isEqualTo(Quantity.of(10));
            assertThat(productService.getStock(shirt)).isEqualTo(Quantity.of(1));
            assertThat(paymentRepository.findByAttemptKey(KEY)).isEmpty();
            assertThat(orderFacade.get(USER, order.getId()).getStatus()).isEqualTo(OrderStatus.DRAFT);
        }
    }
}
