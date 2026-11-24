package com.loopers.domains.order.in;

import com.loopers.utils.DatabaseCleanUp;
import java.sql.Statement;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static com.loopers.domains.order.in.PrometheusScrape.scrape;
import static com.loopers.domains.order.in.PrometheusScrape.unresolved;
import static org.assertj.core.api.Assertions.fail;

// 갱신 스케줄러를 켜고 주기를 200ms로 줄여, DB 상태 변화가 실제 /actuator/prometheus 값에 유한한 대기 안에 반영되는지 본다.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "commerce.payment.unresolved-metric.enabled=true",
    "commerce.payment.unresolved-metric.refresh-interval=200ms"
})
@AutoConfigureObservability
class UnresolvedPaymentMetricsIntegrationTest {
    private static final Duration WAIT_LIMIT = Duration.ofSeconds(5);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(100);

    @LocalManagementPort private int managementPort;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("결제 대기 PG 주문이 하나 생기면 5초 안에 pending 값이 0에서 1로 바뀐다")
    void refreshesValueWhenDatabaseChanges() throws Exception {
        awaitValue("pending", 0.0);

        pendingPgOrder("PENDING");

        awaitValue("pending", 1.0);
    }

    private void awaitValue(String status, double expected) throws Exception {
        long deadline = System.nanoTime() + WAIT_LIMIT.toNanos();
        Optional<Double> last = Optional.empty();
        while (System.nanoTime() < deadline) {
            last = unresolved(scrape(managementPort), status);
            if (last.isPresent() && last.get() == expected) {
                return;
            }
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
        fail("%s 안에 status=%s 값이 %s가 되지 않았다. 마지막 값=%s", WAIT_LIMIT, status, expected,
            last.map(String::valueOf).orElse("시계열 없음"));
    }

    private void pendingPgOrder(String providerStatus) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> connection.prepareStatement(
            "insert into orders(user_id, total, status) values (1, 1000, 'PAYMENT_PENDING')", Statement.RETURN_GENERATED_KEYS), keyHolder);
        String attemptKey = UUID.randomUUID().toString();
        jdbc.update("insert into payment_records(order_id, method, amount, result, attempt_key, provider_id, provider_order_id, "
                + "provider_status) values (?, 'PG', 1000, 'PENDING', ?, 'test-pg', ?, ?)",
            keyHolder.getKey().longValue(), attemptKey, "PO-" + attemptKey.substring(0, 8), providerStatus);
    }
}
