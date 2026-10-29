package com.loopers.domain.payment;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGateway;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

/**
 * PG 성공 뒤 내부 저장(TX B)이 실패하면 전체가 rollback 되고, 같은 결과를 다시 적용하면 한 번만 확정되는지 검증한다 (설계 5장).
 * 실패 주입은 ⑧ 예약 갱신 Repository 를 spy 로 감싸 첫 호출에만 예외를 던지게 한다. @MockitoSpyBean 이 별도 컨텍스트를 만들므로 클래스를 분리했다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class PaymentResultServiceFailureTest {

    private static final String TRANSACTION_KEY = "FAKE-TX-000001";

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;

    private final PaymentResultService paymentResultService;
    private final FakePaymentGateway fakePaymentGateway;
    private final UserJpaRepository userJpaRepository;
    private final ProductJpaRepository productJpaRepository;
    private final OrderJpaRepository orderJpaRepository;
    private final PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    private final StockReservationJpaRepository stockReservationJpaRepository;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    public PaymentResultServiceFailureTest(
        PaymentResultService paymentResultService,
        FakePaymentGateway fakePaymentGateway,
        UserJpaRepository userJpaRepository,
        ProductJpaRepository productJpaRepository,
        OrderJpaRepository orderJpaRepository,
        PaymentAttemptJpaRepository paymentAttemptJpaRepository,
        StockReservationJpaRepository stockReservationJpaRepository,
        JdbcTemplate jdbcTemplate,
        DatabaseCleanUp databaseCleanUp
    ) {
        this.paymentResultService = paymentResultService;
        this.fakePaymentGateway = fakePaymentGateway;
        this.userJpaRepository = userJpaRepository;
        this.productJpaRepository = productJpaRepository;
        this.orderJpaRepository = orderJpaRepository;
        this.paymentAttemptJpaRepository = paymentAttemptJpaRepository;
        this.stockReservationJpaRepository = stockReservationJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @BeforeEach
    void setUp() {
        fakePaymentGateway.reset();
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("PG SUCCESS 를 반영하는 TX B 에서 ⑧ 예약 갱신이 실패하면 전체가 rollback 되고(시도 미종결·주문 PAYMENT_PENDING·예약 RESERVED·재고 3), 같은 결과를 다시 적용하면 CONFIRMED 가 1회 되며 create 는 추가 0회다.")
    @Test
    void rollsBackWholeTxB_andAppliesOnceOnRetry_whenReservationUpdateFailsFirst() {
        // arrange
        Long userId = userJpaRepository.save(new User()).getId();
        Product product = new Product(1L, "상품", new Price(1000L));
        product.changeStock(3);
        Long productId = productJpaRepository.save(product).getId();
        Long orderId = orderJpaRepository.save(new Order(userId, List.of(new OrderItem(productId, 2, 1000L)))).getId();
        jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG' WHERE id = ?", orderId);
        Long reservationId = stockReservationJpaRepository.save(new StockReservation(orderId, productId, 2)).getId();
        Long attemptId = paymentAttemptJpaRepository.save(new PaymentAttempt(
            orderId, userId, "k1", PaymentAttempt.providerOrderIdOf(orderId), 2000L, "TEST")).getId();
        jdbcTemplate.update("UPDATE payment_attempts SET status = 'PENDING', transaction_key = ? WHERE id = ?",
            TRANSACTION_KEY, attemptId);

        doThrow(new IllegalStateException("테스트: 예약 갱신 실패"))
            .doCallRealMethod()
            .when(stockReservationRepository).consumeIfReserved(anyLong());
        PaymentFinalResult success = PaymentFinalResult.success(TRANSACTION_KEY);

        // act 1 - 첫 적용은 ⑧ 에서 실패한다
        IllegalStateException failure = assertThrows(IllegalStateException.class,
            () -> paymentResultService.applyFinal(attemptId, success));

        // assert 1 - ⑥⑦ 까지 포함해 전부 되돌아간다
        assertThat(failure.getMessage()).isEqualTo("테스트: 예약 갱신 실패");
        assertThat(paymentAttemptJpaRepository.findById(attemptId).orElseThrow().getStatus()).isEqualTo(PaymentAttempt.Status.PENDING);
        assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus()).isEqualTo(Order.OrderStatus.PAYMENT_PENDING);
        assertThat(stockReservationJpaRepository.findById(reservationId).orElseThrow().getStatus()).isEqualTo(StockReservation.Status.RESERVED);
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(fakePaymentGateway.createCount()).isZero();

        // act 2 - 같은 결과를 다시 적용한다 (create 재발송 없음)
        PaymentApplyResult applied = paymentResultService.applyFinal(attemptId, success);

        // assert 2 - 첫 시도가 전부 되돌아갔으므로 이번이 주문의 최초 확정이다
        assertThat(applied.applied()).isTrue();
        assertThat(applied.isFirstConfirmation()).isTrue();
        Order order = orderJpaRepository.findById(orderId).orElseThrow();
        assertThat(order.getStatus()).isEqualTo(Order.OrderStatus.CONFIRMED);
        assertThat(order.getPaidAmount()).isEqualTo(2000L);
        assertThat(paymentAttemptJpaRepository.findById(attemptId).orElseThrow().getStatus()).isEqualTo(PaymentAttempt.Status.SUCCESS);
        assertThat(stockReservationJpaRepository.findById(reservationId).orElseThrow().getStatus()).isEqualTo(StockReservation.Status.CONSUMED);
        assertThat(stockOf(productId)).isEqualTo(3);
        assertThat(fakePaymentGateway.createCount()).isZero();
    }

    private int stockOf(Long productId) {
        return productJpaRepository.findById(productId).orElseThrow().getStock().getQuantity();
    }
}