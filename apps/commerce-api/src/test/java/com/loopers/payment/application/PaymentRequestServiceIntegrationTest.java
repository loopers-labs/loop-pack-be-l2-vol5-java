package com.loopers.payment.application;

import com.loopers.brand.adapter.out.persistence.BrandJpaRepository;
import com.loopers.brand.domain.BrandModel;
import com.loopers.common.domain.Money;
import com.loopers.order.adapter.out.persistence.OrderJpaRepository;
import com.loopers.order.application.port.in.OrderCommandUseCase;
import com.loopers.order.domain.OrderLines;
import com.loopers.order.domain.OrderModel;
import com.loopers.order.domain.OrderStatus;
import com.loopers.order.domain.PaymentMethod;
import com.loopers.payment.adapter.out.persistence.PaymentJpaRepository;
import com.loopers.payment.adapter.out.persistence.PaymentOutboxJpaRepository;
import com.loopers.payment.application.port.in.PaymentDispatchUseCase;
import com.loopers.payment.application.port.in.PaymentInfo;
import com.loopers.payment.application.port.in.PaymentRequestCommand;
import com.loopers.payment.application.port.in.PaymentRequestUseCase;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentOutbox;
import com.loopers.payment.domain.PaymentOutboxStatus;
import com.loopers.payment.domain.PaymentStatus;
import com.loopers.point.adapter.out.persistence.PointHistoryJpaRepository;
import com.loopers.point.application.PointQueryService;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.point.domain.PointHistoryType;
import com.loopers.product.adapter.out.persistence.ProductJpaRepository;
import com.loopers.product.domain.ProductModel;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.user.adapter.out.persistence.UserJpaRepository;
import com.loopers.user.domain.UserModel;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 결제 요청 TX A (plan.md 4장, 4-1 멱등, ADR-W5-02). 실제 MySQL로 주문·재고·선점·결제·장부가 함께 commit·rollback되는지 확인한다.
 * 발송(AFTER_COMMIT 이후)은 이 테스트의 대상이 아니므로 발송 입구만 mock으로 바꿔, commit됐을 때만 한 번 불리는지 본다.
 */
@SpringBootTest
class PaymentRequestServiceIntegrationTest {

    private static final String CARD_TYPE = "SAMSUNG";
    private static final String CARD_NO = "1234-5678-9814-1451";

    @Autowired
    private PaymentRequestUseCase paymentRequestUseCase;

    @MockitoBean
    private PaymentDispatchUseCase paymentDispatchUseCase;

    @Autowired
    private OrderCommandUseCase orderCommandUseCase;

    @Autowired
    private PointCommandUseCase pointCommandUseCase;

    @Autowired
    private PointQueryService pointQueryService;

    @Autowired
    private UserJpaRepository userJpaRepository;

    @Autowired
    private BrandJpaRepository brandJpaRepository;

    @Autowired
    private ProductJpaRepository productJpaRepository;

    @Autowired
    private OrderJpaRepository orderJpaRepository;

    @Autowired
    private PaymentJpaRepository paymentJpaRepository;

    @Autowired
    private PaymentOutboxJpaRepository paymentOutboxJpaRepository;

    @Autowired
    private PointHistoryJpaRepository pointHistoryJpaRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private UserModel user;
    private UserModel other;
    private ProductModel airMax;
    private ProductModel airForce;

    @BeforeEach
    void setUp() {
        user = userJpaRepository.save(new UserModel("고객"));
        other = userJpaRepository.save(new UserModel("다른 고객"));
        BrandModel nike = brandJpaRepository.save(new BrandModel("나이키", null));
        airMax = productJpaRepository.save(new ProductModel(nike.getId(), "에어맥스", 1_000, 10));
        airForce = productJpaRepository.save(new ProductModel(nike.getId(), "에어포스", 2_000, 10));
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    /** 에어맥스 1,000원 × 3 + 에어포스 2,000원 × 2 = 7,000원짜리 DRAFT 주문. */
    private Long createOrderOf7000(UserModel owner) {
        return orderCommandUseCase.create(owner.getId(), List.of(
            new OrderLines.Line(airMax.getId(), 3),
            new OrderLines.Line(airForce.getId(), 2)
        )).id();
    }

    private PaymentInfo request(UserModel requester, Long orderId, String attemptKey) {
        return paymentRequestUseCase.request(new PaymentRequestCommand(requester.getId(), orderId, attemptKey, CARD_TYPE, CARD_NO));
    }

    private int stockOf(ProductModel product) {
        return productJpaRepository.findById(product.getId()).orElseThrow().getStock();
    }

    private OrderModel orderOf(Long orderId) {
        return orderJpaRepository.findById(orderId).orElseThrow();
    }

    private List<String> reservationRows(Long orderId) {
        return jdbcTemplate.queryForList(
            "SELECT CONCAT(status, ':', quantity) FROM stock_reservations WHERE order_id = ? ORDER BY product_id", String.class, orderId);
    }

    /** 거절된 요청이 아무것도 남기지 않았는지: 주문 DRAFT, 재고 10·10, 선점·결제·장부 0, 발송 0. */
    private void assertNothingSaved(Long orderId) {
        assertThat(orderOf(orderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
        assertThat(orderOf(orderId).getPaymentMethod()).isNull();
        assertThat(stockOf(airMax)).isEqualTo(10);
        assertThat(stockOf(airForce)).isEqualTo(10);
        assertThat(reservationRows(orderId)).isEmpty();
        assertThat(paymentJpaRepository.count()).isZero();
        assertThat(paymentOutboxJpaRepository.count()).isZero();
        verify(paymentDispatchUseCase, never()).dispatch(any());
    }

    @DisplayName("결제를 요청할 수 있으면, ")
    @Nested
    class Success {

        @DisplayName("S-1 주문 PAYMENT_PENDING(PG)·재고 선점(10→7, 10→8)·결제 REQUESTED 7,000원·장부 PENDING이 함께 저장되고, 포인트는 그대로다.")
        @Test
        void savesOrderReservationPaymentAndOutboxTogether() {
            // arrange
            Long orderId = createOrderOf7000(user);
            pointCommandUseCase.charge(user.getId(), 10_000);

            // act
            PaymentInfo info = request(user, orderId, "attempt-1");

            // assert
            OrderModel order = orderOf(orderId);
            assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(order.getPaymentMethod()).isEqualTo(PaymentMethod.PG);
            assertThat(order.getPaidAmount()).isNull();
            assertThat(stockOf(airMax)).isEqualTo(7);
            assertThat(stockOf(airForce)).isEqualTo(8);
            assertThat(reservationRows(orderId)).containsExactly("RESERVED:3", "RESERVED:2");

            Payment payment = paymentJpaRepository.findByOrderId(orderId).orElseThrow();
            assertThat(payment.getStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(payment.getUserId()).isEqualTo(user.getId());
            assertThat(payment.getAttemptKey()).isEqualTo("attempt-1");
            assertThat(payment.getProvider()).isEqualTo("LOCAL_PG");
            assertThat(payment.getAmount()).isEqualTo(Money.of(7_000));
            List<PaymentOutbox> outboxes = paymentOutboxJpaRepository.findAll();
            assertThat(outboxes).extracting(PaymentOutbox::getPaymentId, PaymentOutbox::getStatus)
                .containsExactly(tuple(payment.getId(), PaymentOutboxStatus.PENDING));

            assertThat(pointQueryService.getBalance(user.getId()).balance()).isEqualTo(10_000);
            assertThat(pointHistoryJpaRepository.findAll()).noneMatch(history -> history.getType() == PointHistoryType.USE);

            assertThat(info.paymentId()).isEqualTo(payment.getId());
            assertThat(info.orderId()).isEqualTo(orderId);
            assertThat(info.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(info.paymentStatus()).isEqualTo(PaymentStatus.REQUESTED);
        }

        @DisplayName("ADR-W5-01 commit된 뒤 발송 입구가 그 결제와 카드 정보로 한 번 불린다.")
        @Test
        void dispatchesOnceAfterCommit() {
            // arrange
            Long orderId = createOrderOf7000(user);

            // act
            PaymentInfo info = request(user, orderId, "attempt-1");

            // assert
            verify(paymentDispatchUseCase, times(1)).dispatch(argThat(event ->
                event.paymentId().equals(info.paymentId()) && CARD_TYPE.equals(event.cardType()) && CARD_NO.equals(event.cardNo())));
        }

        @DisplayName("plan.md 8 카드 번호는 결제 행 어디에도 저장되지 않는다.")
        @Test
        void doesNotStoreCardNumber() {
            // arrange
            Long orderId = createOrderOf7000(user);

            // act
            request(user, orderId, "attempt-1");

            // assert
            String row = jdbcTemplate.queryForList("SELECT * FROM payments").toString();
            assertThat(row).doesNotContain(CARD_NO).doesNotContain("9814");
        }
    }

    @DisplayName("같은 시도 키로 다시 요청하면 (plan.md 4-1), ")
    @Nested
    class Idempotency {

        @DisplayName("S-5 같은 주문이면 새 효과 없이 같은 시도를 돌려준다 — 결제·장부 1, 재고 7·8, 발송 1회.")
        @Test
        void returnsSameAttemptWithoutNewEffects() {
            // arrange
            Long orderId = createOrderOf7000(user);
            PaymentInfo first = request(user, orderId, "attempt-1");

            // act
            PaymentInfo second = request(user, orderId, "attempt-1");

            // assert
            assertThat(second.paymentId()).isEqualTo(first.paymentId());
            assertThat(second.paymentStatus()).isEqualTo(PaymentStatus.REQUESTED);
            assertThat(second.orderStatus()).isEqualTo(OrderStatus.PAYMENT_PENDING);
            assertThat(paymentJpaRepository.count()).isEqualTo(1);
            assertThat(paymentOutboxJpaRepository.count()).isEqualTo(1);
            assertThat(stockOf(airMax)).isEqualTo(7);
            assertThat(stockOf(airForce)).isEqualTo(8);
            assertThat(reservationRows(orderId)).containsExactly("RESERVED:3", "RESERVED:2");
            verify(paymentDispatchUseCase, times(1)).dispatch(any());
        }

        @DisplayName("S-5 다른 주문이면 409이고, 그 주문은 DRAFT·재고 그대로다.")
        @Test
        void rejectsSameKeyForAnotherOrder() {
            // arrange
            Long firstOrderId = createOrderOf7000(user);
            Long secondOrderId = createOrderOf7000(user);
            request(user, firstOrderId, "attempt-1");

            // act & assert
            assertThatThrownBy(() -> request(user, secondOrderId, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(orderOf(secondOrderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(reservationRows(secondOrderId)).isEmpty();
            assertThat(stockOf(airMax)).isEqualTo(7);
            assertThat(paymentJpaRepository.count()).isEqualTo(1);
        }

        @DisplayName("S-5 이미 다른 키로 시도한 주문이면 409이고, 결제는 처음 시도 하나다.")
        @Test
        void rejectsAnotherKeyForSameOrder() {
            // arrange
            Long orderId = createOrderOf7000(user);
            request(user, orderId, "attempt-1");

            // act & assert
            assertThatThrownBy(() -> request(user, orderId, "attempt-2"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(paymentJpaRepository.findAll()).extracting(Payment::getAttemptKey).containsExactly("attempt-1");
            assertThat(stockOf(airMax)).isEqualTo(7);
            verify(paymentDispatchUseCase, times(1)).dispatch(any());
        }
    }

    @DisplayName("결제를 요청할 수 없으면 전체 rollback이고 발송하지 않는다, ")
    @Nested
    class Rejected {

        @DisplayName("S-11 남의 주문이면 404이고, 아무것도 저장되지 않는다.")
        @Test
        void rejectsOthersOrder() {
            // arrange
            Long orderId = createOrderOf7000(user);

            // act & assert
            assertThatThrownBy(() -> request(other, orderId, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
            assertNothingSaved(orderId);
        }

        @DisplayName("없는 주문이면 404다.")
        @Test
        void rejectsMissingOrder() {
            // act & assert
            assertThatThrownBy(() -> request(user, 999L, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.NOT_FOUND);
            assertThat(paymentJpaRepository.count()).isZero();
        }

        @DisplayName("S-11 이전에 포인트로 확정한 주문이면 409이고, 아무것도 저장되지 않는다.")
        @Test
        void rejectsPointConfirmedOrder() {
            // arrange
            Long orderId = createOrderOf7000(user);
            jdbcTemplate.update("UPDATE orders SET status = 'CONFIRMED', payment_method = 'POINT', paid_amount = 7000 WHERE id = ?", orderId);

            // act & assert
            assertThatThrownBy(() -> request(user, orderId, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(OrderStatus.CONFIRMED);
            assertThat(orderOf(orderId).getPaymentMethod()).isEqualTo(PaymentMethod.POINT);
            assertThat(stockOf(airMax)).isEqualTo(10);
            assertThat(paymentJpaRepository.count()).isZero();
            verify(paymentDispatchUseCase, never()).dispatch(any());
        }

        @DisplayName("S-11 재고가 부족하면(에어포스 1개에 2개 주문) 409이고, 먼저 잠근 상품의 재고도 그대로이며 발송 0이다.")
        @Test
        void rejectsShortStock() {
            // arrange
            Long orderId = createOrderOf7000(user);
            jdbcTemplate.update("UPDATE products SET stock = 1 WHERE id = ?", airForce.getId());

            // act & assert
            assertThatThrownBy(() -> request(user, orderId, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertThat(orderOf(orderId).getStatus()).isEqualTo(OrderStatus.DRAFT);
            assertThat(stockOf(airMax)).isEqualTo(10);
            assertThat(stockOf(airForce)).isEqualTo(1);
            assertThat(reservationRows(orderId)).isEmpty();
            assertThat(paymentJpaRepository.count()).isZero();
            assertThat(paymentOutboxJpaRepository.count()).isZero();
            verify(paymentDispatchUseCase, never()).dispatch(any());
        }

        @DisplayName("S-11 주문 뒤 삭제된 상품이 있으면 409이고, 아무것도 저장되지 않는다.")
        @Test
        void rejectsDeletedProduct() {
            // arrange
            Long orderId = createOrderOf7000(user);
            ProductModel deleted = productJpaRepository.findById(airForce.getId()).orElseThrow();
            deleted.delete();
            productJpaRepository.save(deleted);

            // act & assert
            assertThatThrownBy(() -> request(user, orderId, "attempt-1"))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
            assertNothingSaved(orderId);
        }

        @DisplayName("plan.md 2 시도 키(Idempotency-Key)가 없거나 비면 400이고, 아무것도 저장되지 않는다.")
        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  "})
        void rejectsMissingAttemptKey(String attemptKey) {
            // arrange
            Long orderId = createOrderOf7000(user);

            // act & assert
            assertThatThrownBy(() -> request(user, orderId, attemptKey))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertNothingSaved(orderId);
        }

        @DisplayName("plan.md 8 카드 번호가 없으면 400이고, 아무것도 저장되지 않는다.")
        @Test
        void rejectsMissingCard() {
            // arrange
            Long orderId = createOrderOf7000(user);

            // act & assert
            assertThatThrownBy(() -> paymentRequestUseCase.request(new PaymentRequestCommand(user.getId(), orderId, "attempt-1", CARD_TYPE, " ")))
                .isInstanceOf(CoreException.class)
                .extracting("errorType").isEqualTo(ErrorType.BAD_REQUEST);
            assertNothingSaved(orderId);
        }
    }
}
