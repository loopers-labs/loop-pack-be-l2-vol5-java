package com.loopers.application.activity;

import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.PurchaseLine;
import com.loopers.domain.activity.PurchaseReading;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PurchaseActivityIntegrationTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-30T01:02:03.120000Z");

    private final PurchaseActivityTransactions transactions;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    PurchaseActivityIntegrationTest(PurchaseActivityTransactions transactions, JdbcTemplate jdbcTemplate,
                                    DatabaseCleanUp databaseCleanUp) {
        this.transactions = transactions;
        this.jdbcTemplate = jdbcTemplate;
        this.databaseCleanUp = databaseCleanUp;
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private static PurchaseLine line(long productId, int quantity) {
        return new PurchaseLine(productId, quantity);
    }

    private static ConfirmedPurchase purchase(long orderId, PurchaseLine... lines) {
        PurchaseReading reading = ConfirmedPurchase.read(String.valueOf(orderId), "order-confirmed:v1:" + orderId,
            orderId, CONFIRMED_AT, List.of(lines));
        return ((PurchaseReading.Readable) reading).purchase();
    }

    private int count(String table) {
        return jdbcTemplate.queryForObject("select count(*) from " + table, Integer.class);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    @Nested
    @DisplayName("ACT-002, ACT-003, ACT-012, ACT-013: 처리 기록과 모든 품목의 구매 활동을 한 TX 로 남긴다")
    class Record {

        @DisplayName("ACT-013: 주문 42 가 상품 7 을 2 개, 상품 8 을 1 개 사면 활동 2 행, 주문 43 이 상품 7 을 3 개 사면 모두 3 행이고 상품 7 의 수량 합은 5 다")
        @Test
        void recordsReferenceNumbers() {
            transactions.record(purchase(42L, line(7L, 2), line(8L, 1)));
            transactions.record(purchase(43L, line(7L, 3)));

            assertThat(count("order_confirmation_receipt")).isEqualTo(2);
            assertThat(count("purchase_activity")).isEqualTo(3);
            assertThat(jdbcTemplate.queryForObject(
                "select sum(quantity) from purchase_activity where product_id = 7", Integer.class)).isEqualTo(5);
        }

        @DisplayName("ACT-002: 처리 기록은 eventId, 주문, 확정 시각, 정규화한 품목을 남긴다")
        @Test
        void keepsReceipt() {
            transactions.record(purchase(42L, line(8L, 1), line(7L, 2)));

            Map<String, Object> receipt = jdbcTemplate.queryForMap(
                "select event_id, order_id, confirmed_at, items_digest from order_confirmation_receipt");
            assertThat(receipt.get("event_id")).isEqualTo("order-confirmed:v1:42");
            assertThat(((Number) receipt.get("order_id")).longValue()).isEqualTo(42L);
            assertThat(receipt.get("confirmed_at")).isEqualTo(utc(CONFIRMED_AT));
            assertThat(receipt.get("items_digest")).isEqualTo("7:2,8:1");
        }

        @DisplayName("ACT-012: 활동의 확정 시각은 소비한 시각이 아니라 이벤트의 값이다")
        @Test
        void activityKeepsEventTime() {
            transactions.record(purchase(42L, line(7L, 2), line(8L, 1)));

            List<LocalDateTime> times = jdbcTemplate.queryForList(
                "select confirmed_at from purchase_activity where order_id = 42", LocalDateTime.class);
            assertThat(times).containsOnly(utc(CONFIRMED_AT));
        }
    }

    @Nested
    @DisplayName("ACT-003: 품목 하나라도 저장하지 못하면 처리 기록과 앞서 저장한 품목까지 남지 않는다")
    class Atomicity {

        @AfterEach
        void dropInjectedFailure() {
            jdbcTemplate.execute("alter table purchase_activity drop check ck_test_quantity_99");
        }

        @DisplayName("둘째 품목의 저장이 DB 에서 거절되면 처리 기록 0, 활동 0 이다")
        @Test
        void rollsBackEverythingWhenAnItemFails() {
            jdbcTemplate.execute(
                "alter table purchase_activity add constraint ck_test_quantity_99 check (quantity <> 99)");

            assertThatThrownBy(() -> transactions.record(purchase(42L, line(7L, 2), line(8L, 99))))
                .isInstanceOf(RuntimeException.class);

            assertThat(count("order_confirmation_receipt")).isZero();
            assertThat(count("purchase_activity")).isZero();
        }
    }
}
