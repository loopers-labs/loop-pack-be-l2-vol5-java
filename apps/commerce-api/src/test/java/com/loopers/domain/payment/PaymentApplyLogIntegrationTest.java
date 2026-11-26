package com.loopers.domain.payment;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.product.Price;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.StockReservation;
import com.loopers.domain.product.StockReservationRepository;
import com.loopers.domain.user.User;
import com.loopers.infrastructure.order.OrderJpaRepository;
import com.loopers.infrastructure.payment.FakePaymentGatewayConfig;
import com.loopers.infrastructure.payment.PaymentAttemptJpaRepository;
import com.loopers.infrastructure.product.ProductJpaRepository;
import com.loopers.infrastructure.product.StockReservationJpaRepository;
import com.loopers.infrastructure.user.UserJpaRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
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
 * 적용 시도와 실제 commit 이 로그에서 구분되는지 실제 TX 로 검증한다 (10주차 설계 3-2).
 * rollback 되면 phase=committed 가 생기지 않고 phase=rolled-back 이 남아야 한다.
 * 이미 종결된 시도에 같은 결과가 다시 와도 settled=0 으로만 남고 새 확정으로 기록되지 않아야 한다.
 */
@SpringBootTest
@Import(FakePaymentGatewayConfig.class)
class PaymentApplyLogIntegrationTest {

    private static final String TRANSACTION_KEY = "FAKE-TX-000001";
    private static final String CARD_NO = "0000-0000-0000-0000";

    @MockitoSpyBean
    private StockReservationRepository stockReservationRepository;

    @Autowired
    private PaymentResultService paymentResultService;
    @Autowired
    private UserJpaRepository userJpaRepository;
    @Autowired
    private ProductJpaRepository productJpaRepository;
    @Autowired
    private OrderJpaRepository orderJpaRepository;
    @Autowired
    private PaymentAttemptJpaRepository paymentAttemptJpaRepository;
    @Autowired
    private StockReservationJpaRepository stockReservationJpaRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @Test
    @DisplayName("rollback 은 committed 를 남기지 않고 DB 도 되돌아가며, 재적용은 committed, 중복은 settled=0, FAILED 반영도 committed 다.")
    void distinguishesAttemptCommitRollbackAndDuplicate() {
        Long userId = userJpaRepository.save(new User()).getId();
        Product product = new Product(1L, "상품", new Price(1000L));
        product.changeStock(3);
        Long productId = productJpaRepository.save(product).getId();
        Long orderId = orderJpaRepository.save(new Order(userId, List.of(new OrderItem(productId, 2, 1000L)))).getId();
        jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG' WHERE id = ?", orderId);
        stockReservationJpaRepository.save(new StockReservation(orderId, productId, 2));
        Long attemptId = paymentAttemptJpaRepository.save(new PaymentAttempt(
            orderId, userId, "k1", PaymentAttempt.providerOrderIdOf(orderId), 2000L, "TEST")).getId();
        jdbcTemplate.update("UPDATE payment_attempts SET status = 'PENDING', transaction_key = ? WHERE id = ?",
            TRANSACTION_KEY, attemptId);
        PaymentFinalResult success = PaymentFinalResult.success(TRANSACTION_KEY);

        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        root.addAppender(logs);
        try {
            // 1) ⑧ 에서 실패시켜 rollback 을 만든다
            doThrow(new IllegalStateException("테스트: 예약 갱신 실패"))
                .doCallRealMethod()
                .when(stockReservationRepository).consumeIfReserved(anyLong());
            assertThrows(IllegalStateException.class, () -> paymentResultService.applyFinal(attemptId, success));
            List<String> afterRollback = messages(logs);
            assertThat(afterRollback).anyMatch(line -> line.contains("payment.apply phase=attempt")
                && line.contains("settled=1") && line.contains("orderId=" + orderId));
            assertThat(afterRollback).noneMatch(line -> line.contains("payment.apply phase=committed"));
            assertThat(afterRollback).anyMatch(line -> line.contains("payment.apply phase=rolled-back")
                && line.contains("orderId=" + orderId));
            // 로그가 DB 와 일치하는지 본다. rolled-back 이면 저장 상태가 TX 직전으로 남아 있어야 한다.
            assertThat(paymentAttemptJpaRepository.findById(attemptId).orElseThrow().getStatus())
                .isEqualTo(PaymentAttempt.Status.PENDING);
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.PAYMENT_PENDING);

            // 2) 같은 결과를 다시 적용하면 commit 된다
            logs.list.clear();
            assertThat(paymentResultService.applyFinal(attemptId, success).applied()).isTrue();
            List<String> afterCommit = messages(logs);
            assertThat(afterCommit).anyMatch(line -> line.contains("payment.apply phase=attempt") && line.contains("settled=1"));
            assertThat(afterCommit).anyMatch(line -> line.contains("payment.apply phase=committed")
                && line.contains("orderId=" + orderId) && line.contains("appliedStatus=SUCCESS")
                && line.contains("outboxEventId=order-confirmed:v1:" + orderId));
            assertThat(afterCommit).noneMatch(line -> line.contains("phase=rolled-back"));
            assertThat(orderJpaRepository.findById(orderId).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.CONFIRMED);

            // 3) 이미 종결된 시도에 같은 결과가 또 오면 상태를 바꾸지 않는다
            logs.list.clear();
            assertThat(paymentResultService.applyFinal(attemptId, success).applied()).isFalse();
            List<String> afterDuplicate = messages(logs);
            assertThat(afterDuplicate).anyMatch(line -> line.contains("payment.apply phase=attempt")
                && line.contains("settled=0") && line.contains("result=NOT_APPLIED")
                && line.contains("storedStatus=SUCCESS") && line.contains("targetStatus=SUCCESS"));
            assertThat(afterDuplicate).noneMatch(line -> line.contains("phase=committed"));

            // 4) FAILED 결과를 반영한 TX 도 정상적으로 committed 다. committed 는 결제 성공을 뜻하지 않는다.
            logs.list.clear();
            Long failedOrderId = failedPaymentOrder(userId, productId);
            Long failedAttemptId = paymentAttemptJpaRepository.findByOrderId(failedOrderId).orElseThrow().getId();
            assertThat(paymentResultService.applyFinal(failedAttemptId,
                PaymentFinalResult.failed(PaymentAttempt.FailureReason.PG_FAILED, "FAKE-TX-000002")).applied()).isTrue();
            List<String> afterFailed = messages(logs);
            assertThat(afterFailed).anyMatch(line -> line.contains("payment.apply phase=attempt")
                && line.contains("targetStatus=FAILED") && line.contains("settled=1"));
            assertThat(afterFailed).anyMatch(line -> line.contains("payment.apply phase=committed")
                && line.contains("appliedStatus=FAILED") && line.contains("orderId=" + failedOrderId));
            assertThat(afterFailed).noneMatch(line -> line.contains("phase=rolled-back"));
            assertThat(orderJpaRepository.findById(failedOrderId).orElseThrow().getStatus())
                .isEqualTo(Order.OrderStatus.PAYMENT_FAILED);

            // 4) 민감정보가 섞이지 않는다
            assertThat(messagesWithThrowables(logs, afterRollback)).noneMatch(line -> line.contains(CARD_NO));
            assertThat(messagesWithThrowables(logs, afterRollback)).noneMatch(line -> line.contains("cardNo"));
        } finally {
            root.detachAppender(logs);
            logs.stop();
        }
    }

    /** PG 실패 결과를 반영할 대상 주문·예약·시도를 PENDING 상태로 심는다. */
    private Long failedPaymentOrder(Long userId, Long productId) {
        Long orderId = orderJpaRepository.save(new Order(userId, List.of(new OrderItem(productId, 1, 1000L)))).getId();
        jdbcTemplate.update("UPDATE orders SET status = 'PAYMENT_PENDING', payment_method = 'PG' WHERE id = ?", orderId);
        stockReservationJpaRepository.save(new StockReservation(orderId, productId, 1));
        Long attemptId = paymentAttemptJpaRepository.save(new PaymentAttempt(
            orderId, userId, "k2", PaymentAttempt.providerOrderIdOf(orderId), 1000L, "TEST")).getId();
        jdbcTemplate.update("UPDATE payment_attempts SET status = 'PENDING', transaction_key = 'FAKE-TX-000002' WHERE id = ?",
            attemptId);
        return orderId;
    }

    private List<String> messages(ListAppender<ILoggingEvent> logs) {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private List<String> messagesWithThrowables(ListAppender<ILoggingEvent> logs, List<String> earlier) {
        return java.util.stream.Stream.concat(earlier.stream(), messages(logs).stream()).toList();
    }
}
