package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.loopers.domain.mall.model.StockAdjustment;
import com.loopers.domain.mall.repository.StockAdjustmentRepository;
import com.loopers.domain.ordering.model.Order;
import com.loopers.domain.ordering.model.OrderRecord;
import com.loopers.domain.ordering.model.OrderRecordStatus;
import com.loopers.domain.ordering.model.OrderStatus;
import com.loopers.domain.ordering.repository.OrderRepository;
import com.loopers.domain.pay.model.PaymentAttempt;
import com.loopers.domain.pay.model.ProviderPaymentStatus;
import com.loopers.domain.pay.repository.PaymentAttemptRepository;
import com.loopers.infrastructure.persistence.pay.entity.QPaymentAttemptJpaEntity;
import com.querydsl.jpa.impl.JPAQueryFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

record TwentyOrderEvidence(
    long orderId, OrderStatus orderStatus, PaymentAttempt.Outcome paymentOutcome, OrderRecordStatus paymentStatus,
    String attemptKey, String providerOrderId, Instant responseDeadlineAt, long amount, String transactionKey,
    StockAdjustment.State stockState, List<StockAdjustment.Item> stockItems, PgTransaction pgTransaction
) {
    record PgTransaction(String transactionKey, String orderId, long amount, ProviderPaymentStatus status) {}

    @TestComponent
    static class Reader {
        @Autowired private OrderRepository orders;
        @Autowired private PaymentAttemptRepository attempts;
        @Autowired private StockAdjustmentRepository adjustments;
        @Autowired private JPAQueryFactory queries;
        @Autowired @Qualifier("paymentProviderRestClient") private RestClient pg;
        private final TransactionTemplate readTransaction;

        Reader(PlatformTransactionManager transactions) {
            readTransaction = new TransactionTemplate(transactions);
            readTransaction.setReadOnly(true);
            readTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        TwentyOrderEvidence read(long orderId) {
            var commerce = readTransaction.execute(status -> readCommerce(orderId));
            PaymentAttempt attempt = commerce.attempt();
            PgTransaction payment = await().alias("PG 주문 " + attempt.getProviderOrderId() + "의 최종 승인")
                .pollDelay(Duration.ZERO).pollInterval(Duration.ofMillis(150)).atMost(Duration.ofSeconds(15))
                .until(() -> lookup(attempt), row -> row.status() == ProviderPaymentStatus.SUCCESS);
            if (attempt.getOutcome() == PaymentAttempt.Outcome.SUCCESS) {
                assertThat(attempt.getTransactionKey()).as("주문 %s의 확정 거래 키", orderId).isEqualTo(payment.transactionKey());
            }
            return new TwentyOrderEvidence(orderId, commerce.order().getStatus(), attempt.getOutcome(),
                commerce.order().getRecord().map(OrderRecord::getStatus).orElse(null), attempt.getAttemptKey(),
                attempt.getProviderOrderId(), commerce.deadline(), attempt.getAmount(), attempt.getTransactionKey(),
                commerce.stock().getState(), commerce.stock().getItems(), payment);
        }

        private CommerceFacts readCommerce(long orderId) {
            Order order = orders.findById(orderId).orElseThrow();
            PaymentAttempt attempt = attempts.findByOrderId(orderId).orElseThrow();
            StockAdjustment stock = adjustments.findByOrderId(orderId).orElseThrow();
            var payment = QPaymentAttemptJpaEntity.paymentAttemptJpaEntity;
            Instant deadline = queries.select(payment.responseDeadline).from(payment)
                .where(payment.attemptKey.eq(attempt.getAttemptKey())).fetchOne();
            assertThat(deadline).as("주문 %s의 저장된 PG 응답 기한", orderId).isNotNull();
            assertThat(attempt.getDispatchState()).isEqualTo(PaymentAttempt.DispatchState.STARTED);
            assertThat(attempt.getAmount()).isEqualTo(order.getTotalAmount());
            assertThat(stock.getItems()).containsExactlyInAnyOrderElementsOf(order.getItems().stream()
                .map(item -> new StockAdjustment.Item(item.getProductId(), item.getQuantity())).toList());
            return new CommerceFacts(order, attempt, stock, deadline);
        }

        private PgTransaction lookup(PaymentAttempt attempt) {
            List<PgTransaction> rows = pg.get().uri("/api/v1/payments?orderId={orderId}", attempt.getProviderOrderId())
                .header("X-USER-ID", "135135").retrieve().body(new ParameterizedTypeReference<>() {});
            assertThat(rows).as("PG 주문 %s의 거래 목록", attempt.getProviderOrderId()).singleElement().satisfies(row -> {
                assertThat(row.orderId()).isEqualTo(attempt.getProviderOrderId());
                assertThat(row.amount()).isEqualTo(attempt.getAmount());
                assertThat(row.transactionKey()).isNotBlank();
                assertThat(row.status()).isIn(ProviderPaymentStatus.PENDING, ProviderPaymentStatus.SUCCESS);
            });
            return rows.getFirst();
        }

        private record CommerceFacts(Order order, PaymentAttempt attempt, StockAdjustment stock, Instant deadline) {}
    }
}
