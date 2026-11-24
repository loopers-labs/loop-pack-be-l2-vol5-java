package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.UnresolvedPaymentCounter;
import com.loopers.utils.DatabaseCleanUp;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;

import static org.assertj.core.api.Assertions.assertThat;

// 지표 대상 정의: 주문이 PAYMENT_PENDING인 PG 결제 기록만 provider_status별로 센다. 종결 주문과 포인트 결제는 넣지 않는다.
// 상태 조합은 서비스 경로가 만드는 값(2-6절)만 쓰고, 상태마다 건수를 달리해 서로 바뀌어도 드러나게 한다.
@SpringBootTest
class UnresolvedPaymentCounterIntegrationTest {
    @Autowired private UnresolvedPaymentCounter unresolvedPaymentCounter;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DatabaseCleanUp cleanup;

    @AfterEach
    void clean() {
        jdbc.update("delete from order_items");
        cleanup.truncateAllTables();
    }

    @Test
    @DisplayName("결제 대기 주문의 PG 결제만 상태별로 세고 확정·실패·만료 주문과 포인트 결제는 세지 않는다")
    void countsOnlyPendingPgPaymentsByProviderStatus() {
        pgOrders("PAYMENT_PENDING", "PENDING", "SEND_STARTED", 1);
        pgOrders("PAYMENT_PENDING", "PENDING", "PENDING", 2);
        pgOrders("PAYMENT_PENDING", "PENDING", "UNKNOWN", 3);
        pgOrders("PAYMENT_PENDING", "PENDING", "NEEDS_REVIEW", 4);
        pgOrders("CONFIRMED", "SUCCESS", "SUCCESS", 2);
        pgOrders("PAYMENT_FAILED", "FAILED", "FAILED", 1);
        pgOrders("PAYMENT_FAILED", "FAILED", "REJECTED_NO_EFFECT", 1);
        pgOrders("PAYMENT_FAILED", "FAILED", "ALL_NOT_SENT", 1);
        pgOrders("PAYMENT_EXPIRED", "EXPIRED", "EXPIRED", 2);
        pointOrder();

        Map<String, Long> counts = unresolvedPaymentCounter.countByProviderStatus();

        assertThat(counts).isEqualTo(Map.of("SEND_STARTED", 1L, "PENDING", 2L, "UNKNOWN", 3L, "NEEDS_REVIEW", 4L));
    }

    private void pgOrders(String orderStatus, String result, String providerStatus, int count) {
        for (int i = 0; i < count; i++) {
            long orderId = order(orderStatus);
            String attemptKey = UUID.randomUUID().toString();
            jdbc.update("insert into payment_records(order_id, method, amount, result, attempt_key, provider_id, provider_order_id, "
                    + "provider_status) values (?, 'PG', 1000, ?, ?, 'test-pg', ?, ?)",
                orderId, result, attemptKey, "PO-" + attemptKey.substring(0, 8), providerStatus);
        }
    }

    private void pointOrder() {
        long orderId = order("CONFIRMED");
        jdbc.update("insert into payment_records(order_id, method, amount, result) values (?, 'POINT', 1000, 'SUCCESS')", orderId);
    }

    private long order(String status) {
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement(
                "insert into orders(user_id, total, status) values (1, 1000, ?)", Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, status);
            return statement;
        }, keyHolder);
        return keyHolder.getKey().longValue();
    }
}
