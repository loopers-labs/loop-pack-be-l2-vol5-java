package com.loopers.order.infrastructure.monitoring;

import com.loopers.order.application.OrderPaymentTransactionService;
import com.loopers.order.domain.Order;
import com.loopers.order.domain.PaymentGateway;
import com.loopers.product.domain.Product;
import com.loopers.support.fixture.CommerceFixture;
import com.loopers.testcontainers.MySqlTestContainersConfig;
import com.loopers.user.domain.User;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "outbox.relay.enabled=false")
@Import(MySqlTestContainersConfig.class)
@DisplayName("[W10-MONITORING] DB 기반 미확정 결제 gauge")
class PaymentUnresolvedGaugeIntegrationTest {

    private static final String METRIC_NAME = "commerce.payment.unresolved";
    private static final Duration REFRESH_TIMEOUT = Duration.ofSeconds(12);

    @Autowired private MeterRegistry meterRegistry;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private OrderPaymentTransactionService paymentTransactionService;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private CommerceFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new CommerceFixture(entityManager, transactionManager);
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
        fixture.truncateRemainingTables();
    }

    @Test
    @DisplayName("PENDING과 UNKNOWN 주문을 함께 세고 DB 상태가 바뀌면 5초 refresh 안에 gauge를 갱신한다")
    void refreshesUnresolvedPaymentCountFromDatabase() throws Exception {
        User buyer = fixture.user();
        Product product = fixture.product(fixture.brand("metric-" + UUID.randomUUID()), "상품", 1_000L, 4);
        Order pendingOrder = fixture.draftOrder(buyer, fixture.item(product, 1));
        Order unknownOrder = fixture.draftOrder(buyer, fixture.item(product, 1));
        preparePayment(buyer, pendingOrder, false);
        preparePayment(buyer, unknownOrder, true);

        Gauge gauge = awaitGaugeValue(2.0);
        assertThat(gauge.getId().getTags()).isEmpty();

        paymentTransactionService.recordResult(pendingOrder.getId(),
            new PaymentGateway.ProviderError(403, "DECLINED", "declined"));

        awaitGaugeValue(1.0);
    }

    private void preparePayment(User buyer, Order order, boolean unknown) {
        TransactionTemplate transactionTemplate = new TransactionTemplate(transactionManager);
        transactionTemplate.executeWithoutResult(status -> {
            var plan = paymentTransactionService.prepare(buyer.getId(), order.getId(),
                "metric-payment-" + order.getId(), "metric-attempt-" + order.getId());
            if (unknown) {
                plan.order().getPayment().recordResult(new PaymentGateway.Unknown());
                entityManager.flush();
            }
        });
    }

    private Gauge awaitGaugeValue(double expectedValue) throws InterruptedException {
        long deadline = System.nanoTime() + REFRESH_TIMEOUT.toNanos();
        Gauge gauge = null;
        while (System.nanoTime() < deadline) {
            gauge = meterRegistry.find(METRIC_NAME).gauge();
            if (gauge != null && Double.compare(gauge.value(), expectedValue) == 0) {
                return gauge;
            }
            Thread.sleep(50);
        }
        assertThat(gauge)
            .as("metric %s must be registered and refreshed within %s", METRIC_NAME, REFRESH_TIMEOUT)
            .isNotNull();
        assertThat(gauge.value()).isEqualTo(expectedValue);
        return gauge;
    }
}
