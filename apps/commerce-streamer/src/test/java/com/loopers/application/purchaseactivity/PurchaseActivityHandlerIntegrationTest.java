package com.loopers.application.purchaseactivity;

import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode;
import com.loopers.domain.purchaseactivity.PurchaseActivityException;
import com.loopers.interfaces.consumer.order.OrderConfirmedParser;
import com.loopers.testsupport.PurchaseActivityDbTestConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static com.loopers.testsupport.PurchaseActivityDbTestConfig.ACTIVITY;
import static com.loopers.testsupport.PurchaseActivityDbTestConfig.PROCESSED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * C2 정상 효과·C3 동일 사실/충돌/손상을 실제 MySQL 과 handler 로 확인한다. 결과는 매번 새 JDBC 조회로 읽는다.
 * 손상 저장은 parser 를 우회하는 Kafka 입력 대신 직접 SQL 로 만든다(소비 설계 7절 C3).
 */
@SpringBootTest(classes = PurchaseActivityDbTestConfig.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@DisplayName("구매 활동 handler 는 새 사실만 한 번 저장하고 같은 사실은 no-op, 다른 사실·손상은 기존 행을 바꾸지 않는 오류다.")
class PurchaseActivityHandlerIntegrationTest {

    private static final ZonedDateTime AT = ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC);
    private static final String AT_SQL = "2026-10-28 07:00:00.123456";
    private static final String SNAPSHOT = """
        SELECT 'P' AS kind, id, event_id AS a, order_id AS b, event_type AS c, schema_version AS d,
               CAST(confirmed_at AS CHAR) AS e, item_count AS f, CAST(created_at AS CHAR) AS g
        FROM processed_order_confirmation
        UNION ALL
        SELECT 'A', id, NULL, order_id, NULL, product_id, CAST(confirmed_at AS CHAR), quantity, CAST(created_at AS CHAR)
        FROM product_purchase_activity
        ORDER BY kind, id
        """;

    @Autowired
    private PurchaseActivityHandler handler;
    @Autowired
    private OrderConfirmedParser parser;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        PurchaseActivityDbTestConfig.resetTables(jdbc);
    }

    private static OrderConfirmation event(long orderId, ZonedDateTime at, OrderConfirmation.Item... items) {
        return OrderConfirmation.normalize("OrderConfirmed", 1, OrderConfirmation.eventIdOf(orderId), orderId, at, List.of(items));
    }

    private static OrderConfirmation.Item item(long productId, long quantity) {
        return new OrderConfirmation.Item(productId, quantity);
    }

    private List<Map<String, Object>> snapshot() {
        return jdbc.queryForList(SNAPSHOT);
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private PurchaseActivityErrorCode rejected(OrderConfirmation event) {
        PurchaseActivityException error = catchThrowableOfType(PurchaseActivityException.class, () -> handler.consume(event));
        assertThat(error).as("거절되어야 한다").isNotNull();
        return error.getCode();
    }

    private void insertProcessed(String eventId, long orderId, String eventType, int version, String at, int itemCount) {
        jdbc.update("""
            INSERT INTO processed_order_confirmation
                (event_id, order_id, event_type, schema_version, confirmed_at, item_count, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, NOW(6), NOW(6))
            """, eventId, orderId, eventType, version, at, itemCount);
    }

    private void insertActivity(long orderId, long productId, long quantity, String at) {
        jdbc.update("""
            INSERT INTO product_purchase_activity (order_id, product_id, quantity, confirmed_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, NOW(6), NOW(6))
            """, orderId, productId, quantity, at);
    }

    @DisplayName("주문 42 의 7×2·8×1 은 2행, 같은 사실 반복에도 2행, 주문 43 의 7×3 뒤 총 3행·상품 7 합계 5 다.")
    @Test
    void normalEffects() {
        PurchaseActivityResult first = handler.consume(event(42, AT, item(7, 2), item(8, 1)));
        List<Map<String, Object>> afterFirst = snapshot();
        PurchaseActivityResult repeat = handler.consume(event(42, AT, item(8, 1), item(7, 2)));
        List<Map<String, Object>> afterRepeat = snapshot();
        PurchaseActivityResult other = handler.consume(event(43, AT.plusSeconds(1), item(7, 3)));

        List<Map<String, Object>> rows42 = jdbc.queryForList(
            "SELECT product_id, quantity FROM product_purchase_activity WHERE order_id = 42 ORDER BY product_id");
        Map<String, Object> processed42 = jdbc.queryForMap(
            "SELECT event_id, event_type, schema_version, CAST(confirmed_at AS CHAR) AS at, item_count "
                + "FROM processed_order_confirmation WHERE order_id = 42");
        Long product7 = jdbc.queryForObject("SELECT SUM(quantity) FROM product_purchase_activity WHERE product_id = 7", Long.class);
        System.out.println("[C2 EFFECT] first=" + first + " repeat=" + repeat + " other=" + other + " rows=" + snapshot());

        assertAll(
            () -> assertThat(first).isEqualTo(PurchaseActivityResult.APPLIED),
            () -> assertThat(repeat).isEqualTo(PurchaseActivityResult.ALREADY_PROCESSED),
            () -> assertThat(other).isEqualTo(PurchaseActivityResult.APPLIED),
            () -> assertThat(rows42).containsExactly(Map.of("product_id", 7L, "quantity", 2L), Map.of("product_id", 8L, "quantity", 1L)),
            () -> assertThat(processed42).containsExactlyInAnyOrderEntriesOf(Map.of("event_id", "order-confirmed:42",
                "event_type", "OrderConfirmed", "schema_version", 1, "at", AT_SQL, "item_count", 2)),
            () -> assertThat(afterRepeat).isEqualTo(afterFirst),
            () -> assertThat(count(PROCESSED)).isEqualTo(2),
            () -> assertThat(count(ACTIVITY)).isEqualTo(3),
            () -> assertThat(product7).isEqualTo(5L)
        );
    }

    @DisplayName("같은 사실의 다른 JSON 표현(속성·품목 순서, 소수초 후행 0, 같은 상품 분할, 추가 속성)은 parser 를 거쳐 no-op 이다.")
    @Test
    void sameFactThroughParser() {
        ZonedDateTime tenth = ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 100_000_000, ZoneOffset.UTC);
        assertThat(handler.consume(event(42, tenth, item(7, 2), item(8, 1)))).isEqualTo(PurchaseActivityResult.APPLIED);
        List<Map<String, Object>> before = snapshot();
        String json = """
            {"items":[{"quantity":1,"productId":8},{"productId":7,"quantity":1},{"productId":7,"quantity":1,"memo":"x"}],
             "confirmedAt":"2026-10-28T07:00:00.100000Z","orderId":42,"eventId":"order-confirmed:42",
             "schemaVersion":1,"eventType":"OrderConfirmed","traceId":"t-1"}
            """;

        PurchaseActivityResult result = handler.consume(parser.parseAndValidate("42", json.getBytes(StandardCharsets.UTF_8)));

        assertAll(
            () -> assertThat(result).isEqualTo(PurchaseActivityResult.ALREADY_PROCESSED),
            () -> assertThat(snapshot()).isEqualTo(before)
        );
    }

    @DisplayName("parser 가 거절한 입력은 handler 까지 가지 않아 DB 쓰기가 없다.")
    @Test
    void invalidInputWritesNothing() {
        String mismatched = """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:43","orderId":42,
             "confirmedAt":"2026-10-28T07:00:00Z","items":[{"productId":7,"quantity":2}]}
            """;
        PurchaseActivityException error = catchThrowableOfType(PurchaseActivityException.class,
            () -> handler.consume(parser.parseAndValidate("42", mismatched.getBytes(StandardCharsets.UTF_8))));

        assertAll(
            () -> assertThat(error.getCode()).isEqualTo(PurchaseActivityErrorCode.IDENTITY_CONFLICT),
            () -> assertThat(count(PROCESSED)).isZero(),
            () -> assertThat(count(ACTIVITY)).isZero()
        );
    }

    @DisplayName("정상 저장과 수량·상품 집합·확정 시각이 다르면 FACT_CONFLICT 이고 기존 행·추가 효과는 그대로다.")
    @Test
    void factConflicts() {
        handler.consume(event(42, AT, item(7, 2), item(8, 1)));
        List<Map<String, Object>> before = snapshot();

        assertAll(
            () -> assertThat(rejected(event(42, AT, item(7, 3), item(8, 1)))).isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT),
            () -> assertThat(rejected(event(42, AT, item(7, 2)))).isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT),
            () -> assertThat(rejected(event(42, AT, item(7, 2), item(8, 1), item(9, 1)))).isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT),
            () -> assertThat(rejected(event(42, AT.plusNanos(1_000), item(7, 2), item(8, 1)))).isEqualTo(PurchaseActivityErrorCode.FACT_CONFLICT),
            () -> assertThat(snapshot()).isEqualTo(before)
        );
    }

    @Nested
    @DisplayName("저장 손상은 incoming 이 같든 다르든 STORAGE_INTEGRITY_ERROR 이고 행을 바꾸거나 보충하지 않는다.")
    class StorageIntegrity {

        private void assertStorageError(OrderConfirmation incoming) {
            List<Map<String, Object>> before = snapshot();
            assertAll(
                () -> assertThat(rejected(incoming)).isEqualTo(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR),
                () -> assertThat(snapshot()).isEqualTo(before)
            );
        }

        @DisplayName("eventId OR orderId 조회에 서로 다른 완료 2행")
        @Test
        void twoCompletionRows() {
            insertProcessed("order-confirmed:42", 100L, "OrderConfirmed", 1, AT_SQL, 1);
            insertProcessed("order-confirmed:100", 42L, "OrderConfirmed", 1, AT_SQL, 1);
            insertActivity(100L, 7L, 2L, AT_SQL);
            insertActivity(42L, 7L, 2L, AT_SQL);
            assertStorageError(event(42, AT, item(7, 2)));
        }

        @DisplayName("저장 eventId 가 저장 orderId 에서 유도되지 않음(대소문자 차이)")
        @Test
        void storedEventIdNotDerived() {
            insertProcessed("ORDER-CONFIRMED:42", 42L, "OrderConfirmed", 1, AT_SQL, 1);
            insertActivity(42L, 7L, 2L, AT_SQL);
            assertStorageError(event(42, AT, item(7, 2)));
        }

        @DisplayName("저장 eventType·schemaVersion 이 지원 값이 아님")
        @Test
        void storedTypeOrVersion() {
            insertProcessed("order-confirmed:42", 42L, "OrderCancelled", 1, AT_SQL, 1);
            insertActivity(42L, 7L, 2L, AT_SQL);
            assertStorageError(event(42, AT, item(7, 2)));
            PurchaseActivityDbTestConfig.resetTables(jdbc);
            insertProcessed("order-confirmed:42", 42L, "OrderConfirmed", 2, AT_SQL, 1);
            insertActivity(42L, 7L, 2L, AT_SQL);
            assertStorageError(event(42, AT, item(7, 2)));
        }

        @DisplayName("품목 누락(itemCount 2·활동 1행): 같은 사실·다른 사실 incoming 모두")
        @Test
        void missingActivity() {
            insertProcessed("order-confirmed:42", 42L, "OrderConfirmed", 1, AT_SQL, 2);
            insertActivity(42L, 7L, 2L, AT_SQL);
            assertStorageError(event(42, AT, item(7, 2), item(8, 1)));
            assertStorageError(event(42, AT, item(7, 9)));
            assertThat(count(ACTIVITY)).isEqualTo(1);
        }

        @DisplayName("활동의 확정 시각이 완료 시각과 다름")
        @Test
        void activityTimeDiffers() {
            insertProcessed("order-confirmed:42", 42L, "OrderConfirmed", 1, AT_SQL, 1);
            insertActivity(42L, 7L, 2L, "2026-10-28 07:00:00.123457");
            assertStorageError(event(42, AT, item(7, 2)));
        }

        @DisplayName("완료 기록 없는 고아 활동은 새 사건으로 편입하지 않는다.")
        @Test
        void orphanActivity() {
            insertActivity(42L, 7L, 2L, AT_SQL);
            assertStorageError(event(42, AT, item(7, 2)));
            assertThat(count(PROCESSED)).isZero();
        }
    }
}
