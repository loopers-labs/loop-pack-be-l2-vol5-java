package com.loopers.infrastructure.order;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderConfirmedMessageEncoder;
import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderOutboxMessage;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * O2 실제 저장·조회와 저장 TX 경계(설계 W7.2-4~6). 테스트가 연 실제 TX 로 repository 프록시를 호출하고, commit 뒤에는
 * 새 JDBC 조회·새 영속성 컨텍스트로 확인한다. 상태 제외·제약 오류용 SQL 은 테스트 fixture 이며 실제 전달·복구의 증거가 아니다.
 * 결제 확정 TX 와의 원자성(O3/O4)과 broker value 의 이중 인코딩 여부(O5)는 이 테스트의 범위가 아니다.
 */
@SpringBootTest
@DisplayName("Outbox repository 는 호출자 TX 안에서만 저장하고, 저장 원문을 그대로 보존하며, due ID 를 정해진 순서로 조회한다.")
class OrderConfirmedOutboxRepositoryIntegrationTest {

    private static final ZonedDateTime CONFIRMED_AT = ZonedDateTime.of(2026, 10, 28, 7, 0, 0, 123_456_000, ZoneOffset.UTC);
    private static final ZonedDateTime BASE = ZonedDateTime.of(2026, 11, 4, 1, 2, 3, 123_456_000, ZoneOffset.UTC);

    @Autowired
    private OrderConfirmedOutboxRepository repository;
    @Autowired
    private OrderConfirmedMessageEncoder encoder;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private TransactionTemplate writeTx() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate readTx() {
        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setReadOnly(true);
        return template;
    }

    private static OrderConfirmed event(long orderId) {
        return new OrderConfirmed(orderId, CONFIRMED_AT,
            List.of(new OrderConfirmed.Item(7L, 2L), new OrderConfirmed.Item(8L, 1L)));
    }

    /** 실제 encoder 로 만든 메시지를 새 TX 에서 저장하고 commit 한 뒤 행 ID 를 돌려준다. */
    private Long insertCommitted(long orderId, ZonedDateTime firstAttemptAt) {
        OrderOutboxMessage message = encoder.encode(event(orderId));
        return writeTx().execute(status ->
            repository.insertAndFlush(OrderConfirmedOutboxModel.pending(message, firstAttemptAt)).getId());
    }

    private int rowCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM order_confirmed_outbox", Integer.class);
        return count == null ? -1 : count;
    }

    @DisplayName("실제 bean 의 encoder 로 만든 메시지를 commit 하면 새 SQL·새 영속성 컨텍스트에서 key·JSON 원문·초깃값·시각이 그대로다.")
    @Test
    void roundTripsCommittedMessage() throws Exception {
        OrderOutboxMessage message = encoder.encode(event(42L));
        ZonedDateTime firstAttemptAt = BASE;

        Long id = writeTx().execute(status ->
            repository.insertAndFlush(OrderConfirmedOutboxModel.pending(message, firstAttemptAt)).getId());

        Map<String, Object> raw = jdbcTemplate.queryForMap("SELECT * FROM order_confirmed_outbox WHERE id = ?", id);
        LocalDateTime rawNextAttemptAt = jdbcTemplate.queryForObject(
            "SELECT next_attempt_at FROM order_confirmed_outbox WHERE id = ?", LocalDateTime.class, id);
        JsonNode json = objectMapper.readTree((String) raw.get("payload"));
        OrderConfirmedOutboxModel reloaded = readTx().execute(status -> repository.findByEventId("order-confirmed:42")
            .orElseThrow());
        System.out.println("[OUTBOX ROW] " + raw);

        assertAll(
            () -> assertThat(id).isPositive(),
            () -> assertThat(raw).containsEntry("event_id", "order-confirmed:42")
                .containsEntry("order_id", 42L)
                .containsEntry("event_type", "OrderConfirmed")
                .containsEntry("schema_version", 1)
                .containsEntry("message_key", "42")
                .containsEntry("status", "PENDING")
                .containsEntry("attempt_count", 0L)
                .containsEntry("cycle_attempt_count", 0)
                .containsEntry("last_error", null)
                .containsEntry("published_at", null)
                .containsEntry("deleted_at", null),
            () -> assertThat(raw.get("payload")).as("저장 원문 문자열").isEqualTo(message.payload()),
            () -> assertThat(json.isObject()).as("JSON 문자열을 다시 인코딩하지 않은 객체").isTrue(),
            () -> assertThat(json.path("confirmedAt").asText()).isEqualTo("2026-10-28T07:00:00.123456Z"),
            () -> assertThat(json.path("orderId").isIntegralNumber()).isTrue(),
            () -> assertThat(json.path("schemaVersion").isInt()).isTrue(),
            () -> assertThat(json.path("items").toString())
                .isEqualTo("[{\"productId\":7,\"quantity\":2},{\"productId\":8,\"quantity\":1}]"),
            () -> assertThat(rawNextAttemptAt).as("UTC 벽시계·마이크로초")
                .isEqualTo(LocalDateTime.of(2026, 11, 4, 1, 2, 3, 123_456_000)),
            () -> assertThat(raw.get("created_at")).isNotNull(),
            () -> assertThat(reloaded.getId()).isEqualTo(id),
            () -> assertThat(reloaded.getMessageKey()).isEqualTo("42"),
            () -> assertThat(reloaded.getPayload()).isEqualTo(message.payload()),
            () -> assertThat(reloaded.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(reloaded.getAttemptCount()).isZero(),
            () -> assertThat(reloaded.getCycleAttemptCount()).isZero(),
            () -> assertThat(reloaded.getNextAttemptAt().toInstant()).isEqualTo(firstAttemptAt.toInstant()),
            () -> assertThat(reloaded.getLastError()).isNull(),
            () -> assertThat(reloaded.getPublishedAt()).isNull()
        );
    }

    @DisplayName("TX 없이 프록시의 insertAndFlush 를 호출하면 거절되고 행이 생기지 않는다.")
    @Test
    void rejectsInsertWithoutTransaction() {
        OrderConfirmedOutboxModel outbox = OrderConfirmedOutboxModel.pending(encoder.encode(event(42L)), BASE);

        assertAll(
            () -> assertThat(AopUtils.isAopProxy(repository)).as("TX 프록시를 거친 호출").isTrue(),
            () -> assertThatThrownBy(() -> repository.insertAndFlush(outbox))
                .isInstanceOf(IllegalTransactionStateException.class),
            () -> assertThat(rowCount()).isZero()
        );
    }

    @DisplayName("실제 insert·flush 뒤 같은 TX 가 실패하면 새 행은 rollback 된다. flush 성공은 commit 이 아니다.")
    @Test
    void rollsBackAfterInsertAndFlush() {
        OrderConfirmedOutboxModel outbox = OrderConfirmedOutboxModel.pending(encoder.encode(event(42L)), BASE);
        AtomicReference<Long> flushedId = new AtomicReference<>();

        assertThatThrownBy(() -> writeTx().executeWithoutResult(status -> {
            flushedId.set(repository.insertAndFlush(outbox).getId());
            throw new IllegalStateException("insert 뒤 실패 주입");
        })).isInstanceOf(IllegalStateException.class).hasMessage("insert 뒤 실패 주입");

        assertAll(
            () -> assertThat(flushedId.get()).as("IDENTITY insert 가 실제로 실행되어 ID 를 받음").isPositive(),
            () -> assertThat(rowCount()).as("새 연결의 조회").isZero()
        );
    }

    @DisplayName("같은 eventId 의 두 번째 insert 는 uk_oco_event 로 실패하고 기존 행은 그대로다.")
    @Test
    void rejectsDuplicateEventId() {
        Long firstId = insertCommitted(42L, BASE);
        String firstPayload = jdbcTemplate.queryForObject(
            "SELECT payload FROM order_confirmed_outbox WHERE id = ?", String.class, firstId);
        OrderConfirmedOutboxModel duplicate = OrderConfirmedOutboxModel.pending(encoder.encode(event(42L)), BASE.plusSeconds(1));

        // 실패한 TX 안에서는 더 조회하지 않고, TX 종료 뒤 새 연결로 확인한다.
        assertThatThrownBy(() -> writeTx().executeWithoutResult(status -> repository.insertAndFlush(duplicate)))
            .isInstanceOf(DataIntegrityViolationException.class)
            .hasStackTraceContaining("uk_oco_event");

        assertAll(
            () -> assertThat(rowCount()).isEqualTo(1),
            () -> assertThat(jdbcTemplate.queryForMap("SELECT id, payload, next_attempt_at FROM order_confirmed_outbox"))
                .containsEntry("id", firstId)
                .containsEntry("payload", firstPayload)
                .containsEntry("next_attempt_at", LocalDateTime.of(2026, 11, 4, 1, 2, 3, 123_456_000))
        );
    }

    @DisplayName("주문 ID·두 시도 횟수 CHECK 는 직접 SQL 쓰기도 거절한다(MySQL 3819).")
    @Test
    void enforcesCheckConstraints() {
        String insert = """
            INSERT INTO order_confirmed_outbox (event_id, order_id, event_type, schema_version, message_key, payload, status,
                attempt_count, cycle_attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (?, ?, 'OrderConfirmed', 1, ?, '{}', 'PENDING', ?, ?, NOW(6), NOW(6), NOW(6))
            """;
        List<Object[]> violations = new ArrayList<>();
        violations.add(new Object[] {"order-confirmed:0", 0L, "0", 0L, 0});
        violations.add(new Object[] {"order-confirmed:1", 1L, "1", -1L, 0});
        violations.add(new Object[] {"order-confirmed:2", 2L, "2", 0L, -1});

        assertAll(violations.stream().map(args -> (Executable) () -> assertThatThrownBy(() -> jdbcTemplate.update(insert, args))
            .isInstanceOf(UncategorizedSQLException.class)
            .satisfies(error -> assertThat(((UncategorizedSQLException) error).getSQLException().getErrorCode())
                .isEqualTo(3819))));
        assertThat(rowCount()).isZero();
    }

    @DisplayName("due 조회는 PENDING·now 이하만 시각→ID 순으로 limit 개 ID 로 반환하고 미래·PUBLISHED·BLOCKED 를 제외한다.")
    @Test
    void findsPendingDueIdsInOrder() {
        ZonedDateTime now = BASE;
        Long tieLater = insertCommitted(11L, now.minusSeconds(10));
        Long tieEarlier = insertCommitted(12L, now.minusSeconds(10));
        Long oldest = insertCommitted(13L, now.minusSeconds(20));
        Long atNow = insertCommitted(14L, now);
        Long future = insertCommitted(15L, now.plusNanos(1_000));
        Long published = insertCommitted(16L, now.minusSeconds(30));
        Long blocked = insertCommitted(17L, now.minusSeconds(30));
        // 테스트 전용 상태 fixture. 제품 전달·차단 동작의 결과가 아니다.
        jdbcTemplate.update("UPDATE order_confirmed_outbox SET status = 'PUBLISHED' WHERE id = ?", published);
        jdbcTemplate.update("UPDATE order_confirmed_outbox SET status = 'BLOCKED' WHERE id = ?", blocked);

        List<Long> all = readTx().execute(status -> repository.findPendingDueIds(now, 10));
        List<Long> limited = readTx().execute(status -> repository.findPendingDueIds(now, 2));
        List<Long> beforeAll = readTx().execute(status -> repository.findPendingDueIds(now.minusSeconds(21), 10));

        assertAll(
            () -> assertThat(tieLater).isLessThan(tieEarlier),
            () -> assertThat(all).as("now 포함·시각 동률은 ID 순").containsExactly(oldest, tieLater, tieEarlier, atNow),
            () -> assertThat(all).doesNotContain(future, published, blocked),
            () -> assertThat(limited).containsExactly(oldest, tieLater),
            () -> assertThat(beforeAll).isEmpty()
        );
    }

    @DisplayName("due 조회 limit 이 0 이하이거나 기준 시각이 없으면 조회 없이 거절한다.")
    @Test
    void rejectsInvalidDueQueryInput() {
        assertAll(
            () -> assertThatThrownBy(() -> repository.findPendingDueIds(BASE, 0))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> repository.findPendingDueIds(BASE, -1))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR),
            () -> assertThatThrownBy(() -> repository.findPendingDueIds(null, 10))
                .isInstanceOf(CoreException.class).extracting("errorType").isEqualTo(ErrorType.INTERNAL_ERROR)
        );
    }

    /**
     * 같은 조건·정렬의 SQL 실행계획을 기록한다. 작은 테이블에서는 optimizer 가 다른 접근을 고를 수 있으므로 사용 가능한
     * 후보에 idx_oco_due 가 있는지만 단언하고, 실제 선택 결과는 출력으로 남긴다. 성능·처리량의 근거가 아니다.
     */
    @DisplayName("due 조회 SQL 의 EXPLAIN 후보 인덱스에 idx_oco_due 가 있다.")
    @Test
    void explainsDueQuery() {
        for (long orderId = 1; orderId <= 20; orderId++) {
            insertCommitted(orderId, BASE.minusSeconds(orderId));
        }
        String dueSql = """
            SELECT id FROM order_confirmed_outbox
            WHERE status = 'PENDING' AND next_attempt_at <= '2026-11-04 01:02:03.123456'
            ORDER BY next_attempt_at ASC, id ASC LIMIT 10
            """;

        List<Map<String, Object>> plan = jdbcTemplate.queryForList("EXPLAIN " + dueSql);
        String tree = jdbcTemplate.queryForObject("EXPLAIN FORMAT=TREE " + dueSql, String.class);
        System.out.println("[OUTBOX EXPLAIN] " + plan);
        System.out.println("[OUTBOX EXPLAIN TREE] " + tree);

        assertThat(plan).singleElement()
            .satisfies(row -> assertThat(String.valueOf(row.get("possible_keys"))).contains("idx_oco_due"));
    }
}
