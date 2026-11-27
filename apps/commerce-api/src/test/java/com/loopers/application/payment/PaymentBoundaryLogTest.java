package com.loopers.application.payment;

import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.log.LogCapture;
import com.loopers.support.log.LogCapture.Captured;
import com.loopers.support.pg.FakePgServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * "PG SUCCESS + 내부 미확정" 이라는 같은 증상에서 응답 유실과 내부 반영 실패를 가를 경계 로그가 주문 식별자와 함께 남는지 본다.
 */
class PaymentBoundaryLogTest extends PaymentFlowTestSupport {

    @Autowired
    private OutboxRepository outboxRepository;

    @DisplayName("PG 가 거래를 저장한 뒤 응답 없이 연결을 끊으면, create 경계 로그에 결과 UNKNOWN·원인 IO 가 주문 식별자와 함께 남는다.")
    @Test
    void createResponseLost_logsUnknownWithIoCause() {
        // arrange
        User user = saveUser(0);
        Product product = saveProduct("상품", 1_000, 5);
        Long orderId = placeOrder(user, product, 1);
        pg.onCreate(FakePgServer.acceptThenDropResponse());

        try (LogCapture logs = LogCapture.start()) {
            // act
            orderFacade.confirm(command(user, orderId, "log-attempt-1"));

            // assert
            List<Captured> results = logs.events("payment.create.result");
            assertThat(results).hasSize(1);
            Captured result = results.get(0);
            assertThat(result.keyValues())
                .containsEntry("providerOrderId", providerOrderIdOf(orderId))
                .containsEntry("result", "UNKNOWN")
                .containsEntry("cause", "IO");
            assertThat(result.mdc()).containsEntry("orderId", String.valueOf(orderId));
        }
    }

    @DisplayName("PG 성공을 반영하는 TX 가 rollback 되면, 반영 실패 로그가 주문 식별자와 함께 남고 시도는 종결되지 않는다.")
    @Test
    void applyRollback_logsRolledBackUnderOrderId() {
        // arrange: 같은 eventId 의 Outbox 가 이미 있어 확정 TX 가 유일 제약에 걸린다.
        User user = saveUser(0);
        Product product = saveProduct("상품", 1_000, 5);
        Long orderId = placeOrder(user, product, 1);
        orderFacade.confirm(command(user, orderId, "log-attempt-2"));
        pg.setStatus(pgKeyOf(orderId), "SUCCESS");
        outboxRepository.save(Outbox.record(
            "order-confirmed:v1:" + orderId, orderId, "OrderConfirmed", "v1", String.valueOf(orderId), new byte[]{0x01},
            ZonedDateTime.now()
        ));

        try (LogCapture logs = LogCapture.start()) {
            // act
            assertThatThrownBy(() -> paymentResultLookupFacade.lookupMyPaymentResult(user.getId(), orderId))
                .isInstanceOf(RuntimeException.class);

            // assert
            assertThat(logs.events("payment.lookup.result")).singleElement()
                .satisfies(lookup -> assertThat(lookup.keyValues()).containsEntry("pgStatus", "SUCCESS"));
            List<Captured> rollbacks = logs.events("payment.apply.rolled_back");
            assertThat(rollbacks).hasSize(1);
            assertThat(rollbacks.get(0).level()).isEqualTo("ERROR");
            assertThat(rollbacks.get(0).mdc()).containsEntry("orderId", String.valueOf(orderId));
            assertThat(logs.events("payment.apply.committed")).isEmpty();
        }
        assertThat(attemptOf(orderId).get("status")).isEqualTo("PENDING");
        assertThat(orderStatusOf(orderId)).isEqualTo("PAYMENT_PENDING");
    }
}
