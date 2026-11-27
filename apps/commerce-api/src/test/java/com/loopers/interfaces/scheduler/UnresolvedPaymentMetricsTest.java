package com.loopers.interfaces.scheduler;

import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.pg.FakePgServer;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/** 미종결 결제 gauge 가 실제 DB 상태를 유한한 지연 안에 따라가는지 본다. 갱신 주기를 짧게 둔다. */
@TestPropertySource(properties = "payment.metrics.unresolved-refresh-interval=200ms")
class UnresolvedPaymentMetricsTest extends PaymentFlowTestSupport {

    private static final Duration WAIT = Duration.ofSeconds(5);

    @Autowired
    private MeterRegistry meterRegistry;

    @DisplayName("응답 유실로 UNKNOWN 시도가 생기면 UNKNOWN gauge 가 1·대기시간이 늘고, reconcile 로 종결하면 둘 다 0 으로 돌아온다.")
    @Test
    void gaugeFollowsUnknownAttempt() {
        // arrange
        User user = saveUser(0);
        Product product = saveProduct("상품", 1_000, 5);
        Long orderId = placeOrder(user, product, 1);
        pg.onCreate(FakePgServer.acceptThenDropResponse());

        // act: 결과를 잃은 결제
        orderFacade.confirm(command(user, orderId, "metric-attempt-1"));

        // assert
        await().atMost(WAIT).untilAsserted(() -> assertThat(unknown(UnresolvedPaymentMetrics.COUNT)).isEqualTo(1));
        await().atMost(WAIT).untilAsserted(() -> assertThat(unknown(UnresolvedPaymentMetrics.OLDEST_AGE)).isPositive());

        // act: 같은 거래를 조회해 종결
        pg.setStatus(pgKeyOf(orderId), "SUCCESS");
        paymentResultLookupFacade.lookupMyPaymentResult(user.getId(), orderId);

        // assert
        await().atMost(WAIT).untilAsserted(() -> {
            assertThat(unknown(UnresolvedPaymentMetrics.COUNT)).isZero();
            assertThat(unknown(UnresolvedPaymentMetrics.OLDEST_AGE)).isZero();
        });
    }

    private double unknown(String name) {
        return meterRegistry.get(name).tag("status", "UNKNOWN").gauge().value();
    }
}
