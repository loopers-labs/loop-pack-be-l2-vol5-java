package com.loopers.application.activity;

import com.loopers.domain.activity.ConfirmedPurchase;
import com.loopers.domain.activity.PurchaseLine;
import com.loopers.domain.activity.PurchaseReading;
import com.loopers.domain.activity.ReceivedRecord;
import com.loopers.domain.activity.RecordPosition;
import com.loopers.domain.activity.UnreadableReason;
import com.loopers.utils.DatabaseCleanUp;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class PurchaseActivityIntegrationTest {

    private static final Instant CONFIRMED_AT = Instant.parse("2026-10-30T01:02:03.120000Z");

    private static final String TOPIC = "order-events";
    private static final Instant SENT_AT = Instant.parse("2026-10-30T01:02:04.500000Z");

    private final PurchaseActivityTransactions transactions;
    private final PurchaseActivityHandler handler;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbcTemplate;
    private final DatabaseCleanUp databaseCleanUp;

    @Autowired
    PurchaseActivityIntegrationTest(PurchaseActivityTransactions transactions, PurchaseActivityHandler handler,
                                    MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate,
                                    DatabaseCleanUp databaseCleanUp) {
        this.transactions = transactions;
        this.handler = handler;
        this.meterRegistry = meterRegistry;
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
        return purchase("order-confirmed:v1:" + orderId, orderId, lines);
    }

    private static ConfirmedPurchase purchase(String eventId, long orderId, PurchaseLine... lines) {
        PurchaseReading reading = ConfirmedPurchase.read(String.valueOf(orderId), eventId, orderId, CONFIRMED_AT,
            List.of(lines));
        return ((PurchaseReading.Readable) reading).purchase();
    }

    private static RecordPosition position(long offset) {
        return new RecordPosition(TOPIC, 1, offset);
    }

    private static byte[] payload(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static ReceivedRecord received(String key, long offset, String payload) {
        return new ReceivedRecord(position(offset), key,
            Map.of("traceparent", "00-trace-" + offset + "-01"), SENT_AT,
            payload(payload));
    }

    private PurchaseOutcome handle(ConfirmedPurchase purchase, long offset) {
        return handler.handle(new PurchaseReading.Readable(purchase),
            received(String.valueOf(purchase.orderId()), offset, "record-" + offset));
    }

    private PurchaseOutcome handleUnreadable(String key, long offset, String payload) {
        return handler.handle(new PurchaseReading.Unreadable(UnreadableReason.MALFORMED),
            received(key, offset, payload));
    }

    private double outcomes(String outcome, String reason) {
        Counter counter = meterRegistry.find("purchase.activity").tags("outcome", outcome, "reason", reason).counter();
        return counter == null ? 0 : counter.count();
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

    @Nested
    @DisplayName("ACT-004, ACT-005, ACT-008: 처리 기록과 부딪힌 record 는 rollback 뒤 새 TX 에서 기존 기록과 견주어 가른다")
    class Reclassify {

        @DisplayName("ACT-004: 같은 record 가 다시 오면 효과 없이 정상 완료한다. 처리 기록 1, 활동 2 그대로, 보류 0")
        @Test
        void redeliveryIsNoOp() {
            ConfirmedPurchase purchase = purchase(42L, line(7L, 2), line(8L, 1));

            assertThat(handle(purchase, 10L)).isEqualTo(PurchaseOutcome.RECORDED);
            assertThat(handle(purchase, 10L)).isEqualTo(PurchaseOutcome.SAME_FACT);

            assertThat(count("order_confirmation_receipt")).isEqualTo(1);
            assertThat(count("purchase_activity")).isEqualTo(2);
            assertThat(count("order_event_hold")).isZero();
        }

        @DisplayName("ACT-004, ACT-006: 확정 시각에 마이크로초보다 작은 자리가 있어도, 같은 record 가 다시 오면 같은 사실이다")
        @Test
        void redeliveryWithSubMicrosIsNoOp() {
            PurchaseReading reading = ConfirmedPurchase.read("42", "order-confirmed:v1:42", 42L,
                Instant.parse("2026-10-30T01:02:03.1234567Z"), List.of(line(7L, 2)));
            ConfirmedPurchase purchase = ((PurchaseReading.Readable) reading).purchase();

            handle(purchase, 10L);

            assertThat(handle(purchase, 10L)).isEqualTo(PurchaseOutcome.SAME_FACT);
            assertThat(count("order_event_hold")).isZero();
        }

        @DisplayName("ACT-005: 같은 eventId 에 수량이 다르면 활동을 더하지 않고 원본과 위치를 보류 기록에 남긴다")
        @Test
        void sameEventDifferentFactIsHeld() {
            handle(purchase(42L, line(7L, 2), line(8L, 1)), 10L);

            assertThat(handle(purchase(42L, line(7L, 3), line(8L, 1)), 11L)).isEqualTo(PurchaseOutcome.HELD);

            assertThat(jdbcTemplate.queryForObject(
                "select sum(quantity) from purchase_activity where product_id = 7", Integer.class)).isEqualTo(2);
            Map<String, Object> hold = jdbcTemplate.queryForMap(
                "select reason, event_id, order_id, record_key, headers, topic, partition_no, offset_no, payload"
                    + " from order_event_hold");
            assertThat(hold.get("reason")).isEqualTo("EVENT_CONFLICT");
            assertThat(hold.get("event_id")).isEqualTo("order-confirmed:v1:42");
            assertThat(((Number) hold.get("order_id")).longValue()).isEqualTo(42L);
            assertThat(hold.get("record_key")).isEqualTo("42");
            assertThat((String) hold.get("headers")).contains("\"traceparent\":\"00-trace-11-01\"");
            assertThat(hold.get("topic")).isEqualTo(TOPIC);
            assertThat(((Number) hold.get("partition_no")).intValue()).isEqualTo(1);
            assertThat(((Number) hold.get("offset_no")).longValue()).isEqualTo(11L);
            assertThat((byte[]) hold.get("payload")).isEqualTo(payload("record-11"));
        }

        @DisplayName("ACT-005: 같은 주문에 다른 eventId 가 오면 내용이 같아도 활동을 더하지 않고 보류한다")
        @Test
        void sameOrderDifferentEventIsHeld() {
            handle(purchase(42L, line(7L, 2), line(8L, 1)), 10L);

            assertThat(handle(purchase("order-confirmed:v2:42", 42L, line(7L, 2), line(8L, 1)), 11L))
                .isEqualTo(PurchaseOutcome.HELD);

            assertThat(count("purchase_activity")).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("select reason from order_event_hold", String.class))
                .isEqualTo("ORDER_CONFLICT");
        }

        @DisplayName("ACT-008: 처리 기록이 아니라 고아 활동 행과 부딪히면 중복이 아니므로 원래 오류로 실패한다. 처리 기록 0, 보류 0")
        @Test
        void orphanActivityCollisionFails() {
            jdbcTemplate.update("insert into purchase_activity (order_id, product_id, quantity, confirmed_at,"
                + " created_at, updated_at) values (42, 7, 2, now(6), now(6), now(6))");

            assertThatThrownBy(() -> handle(purchase(42L, line(7L, 2), line(8L, 1)), 10L))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("uk_activity_order_product");

            assertThat(count("order_confirmation_receipt")).isZero();
            assertThat(count("order_event_hold")).isZero();
        }
    }

    @Nested
    @DisplayName("ACT-005, ACT-009: 보류 기록은 원본과 위치를 남기고, 위치가 같으면 한 줄이다")
    class Hold {

        @DisplayName("ACT-009: 읽을 수 없는 record 는 이유, 원본 key, header, timestamp, 바이트, 위치를 보류 기록에 남기고 정상 완료한다")
        @Test
        void holdsUnreadable() {
            PurchaseOutcome outcome = handleUnreadable("43", 20L, "{broken");

            assertThat(outcome).isEqualTo(PurchaseOutcome.HELD);
            Map<String, Object> hold = jdbcTemplate.queryForMap(
                "select reason, event_id, order_id, record_key, headers, record_timestamp, offset_no, payload"
                    + " from order_event_hold");
            assertThat(hold.get("reason")).isEqualTo("MALFORMED");
            assertThat(hold.get("event_id")).isNull();
            assertThat(hold.get("order_id")).isNull();
            assertThat(hold.get("record_key")).isEqualTo("43");
            assertThat((String) hold.get("headers")).contains("\"traceparent\":\"00-trace-20-01\"");
            assertThat(hold.get("record_timestamp")).isEqualTo(utc(SENT_AT));
            assertThat(((Number) hold.get("offset_no")).longValue()).isEqualTo(20L);
            assertThat((byte[]) hold.get("payload")).isEqualTo(payload("{broken"));
            assertThat(count("purchase_activity")).isZero();
        }

        @DisplayName("ACT-005: 보류한 뒤 ack 전에 죽어 같은 record 가 다시 와도 보류는 한 줄이고 정상 완료한다")
        @Test
        void sameRecordIsHeldOnce() {
            handleUnreadable("42", 20L, "{broken");
            PurchaseOutcome again = handleUnreadable("42", 20L, "{broken");

            assertThat(again).isEqualTo(PurchaseOutcome.ALREADY_HELD);
            assertThat(count("order_event_hold")).isEqualTo(1);
        }

        @DisplayName("ACT-005: 충돌로 보류한 record 가 다시 와도 보류는 한 줄이다")
        @Test
        void conflictIsHeldOnce() {
            handle(purchase(42L, line(7L, 2), line(8L, 1)), 10L);
            ConfirmedPurchase changed = purchase(42L, line(7L, 3), line(8L, 1));

            handle(changed, 11L);
            assertThat(handle(changed, 11L)).isEqualTo(PurchaseOutcome.ALREADY_HELD);

            assertThat(count("order_event_hold")).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("ACT-005: 처리 결과를 지표로 센다. 보류는 업무 완료가 아니라 알림의 근거다")
    class Metrics {

        @DisplayName("기록, 같은 사실, 충돌 보류, 이미 보류를 결과와 이유별로 하나씩 센다")
        @Test
        void countsEachOutcome() {
            double recorded = outcomes("recorded", "none");
            double sameFact = outcomes("same_fact", "none");
            double held = outcomes("held", "EVENT_CONFLICT");
            double alreadyHeld = outcomes("already_held", "EVENT_CONFLICT");
            ConfirmedPurchase first = purchase(42L, line(7L, 2), line(8L, 1));
            ConfirmedPurchase changed = purchase(42L, line(7L, 3), line(8L, 1));

            handle(first, 10L);
            handle(first, 10L);
            handle(changed, 11L);
            handle(changed, 11L);

            assertThat(outcomes("recorded", "none") - recorded).isEqualTo(1);
            assertThat(outcomes("same_fact", "none") - sameFact).isEqualTo(1);
            assertThat(outcomes("held", "EVENT_CONFLICT") - held).isEqualTo(1);
            assertThat(outcomes("already_held", "EVENT_CONFLICT") - alreadyHeld).isEqualTo(1);
        }

        @DisplayName("읽을 수 없어 보류한 것은 그 이유로 센다")
        @Test
        void countsUnreadableByReason() {
            double before = outcomes("held", "MALFORMED");

            handleUnreadable("42", 20L, "{broken");

            assertThat(outcomes("held", "MALFORMED") - before).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("ACT-007: 같은 이벤트를 동시에 소비해도 유일 제약이 하나만 남긴다")
    class Concurrency {

        private List<PurchaseOutcome> handleConcurrently(List<ConfirmedPurchase> purchases) throws Exception {
            ExecutorService executor = Executors.newFixedThreadPool(purchases.size());
            CountDownLatch start = new CountDownLatch(1);
            try {
                List<Future<PurchaseOutcome>> futures = new ArrayList<>();
                for (int i = 0; i < purchases.size(); i++) {
                    ConfirmedPurchase purchase = purchases.get(i);
                    long offset = 100L + i;
                    futures.add(executor.submit(() -> {
                        start.await();
                        return handle(purchase, offset);
                    }));
                }
                start.countDown();
                List<PurchaseOutcome> outcomes = new ArrayList<>();
                for (Future<PurchaseOutcome> future : futures) {
                    outcomes.add(future.get(30, TimeUnit.SECONDS));
                }
                return outcomes;
            } finally {
                executor.shutdownNow();
            }
        }

        @DisplayName("같은 사실 2 건이 동시에 오면 하나는 기록, 하나는 같은 사실이고, 처리 기록 1, 활동 한 벌, 보류 0 이다")
        @Test
        void sameFactTwice() throws Exception {
            ConfirmedPurchase purchase = purchase(42L, line(7L, 2), line(8L, 1));

            List<PurchaseOutcome> outcomes = handleConcurrently(List.of(purchase, purchase));

            assertThat(outcomes).containsExactlyInAnyOrder(PurchaseOutcome.RECORDED, PurchaseOutcome.SAME_FACT);
            assertThat(count("order_confirmation_receipt")).isEqualTo(1);
            assertThat(count("purchase_activity")).isEqualTo(2);
            assertThat(count("order_event_hold")).isZero();
        }

        @DisplayName("같은 사실 10 건이 동시에 와도 기록은 1 건이고 나머지 9 건은 같은 사실이다")
        @Test
        void sameFactTenTimes() throws Exception {
            ConfirmedPurchase purchase = purchase(42L, line(7L, 2), line(8L, 1));

            List<PurchaseOutcome> outcomes = handleConcurrently(Collections.nCopies(10, purchase));

            assertThat(outcomes).filteredOn(PurchaseOutcome.RECORDED::equals).hasSize(1);
            assertThat(outcomes).filteredOn(PurchaseOutcome.SAME_FACT::equals).hasSize(9);
            assertThat(count("order_confirmation_receipt")).isEqualTo(1);
            assertThat(count("purchase_activity")).isEqualTo(2);
        }

        @DisplayName("같은 eventId 의 다른 사실 2 건이 동시에 오면 먼저 commit 한 쪽이 남고 다른 쪽은 보류된다")
        @Test
        void conflictingFactsAtOnce() throws Exception {
            List<PurchaseOutcome> outcomes = handleConcurrently(List.of(
                purchase(42L, line(7L, 2), line(8L, 1)),
                purchase(42L, line(7L, 3), line(8L, 1))));

            assertThat(outcomes).containsExactlyInAnyOrder(PurchaseOutcome.RECORDED, PurchaseOutcome.HELD);
            assertThat(count("order_confirmation_receipt")).isEqualTo(1);
            assertThat(count("purchase_activity")).isEqualTo(2);
            assertThat(jdbcTemplate.queryForObject("select reason from order_event_hold", String.class))
                .isEqualTo("EVENT_CONFLICT");
        }
    }
}
