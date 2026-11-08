package com.loopers.infrastructure.order.outbox;

import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OrderConfirmedOutboxStateServiceIntegrationTest {

    private static final String EVENT_ID = "order-confirmed:v1:1";
    private static final ZonedDateTime CREATED_AT = ZonedDateTime.of(2026, 11, 8, 1, 0, 0, 0, ZoneOffset.UTC);
    private static final Duration MAX_WAIT = Duration.ofSeconds(7);
    private static final int MAX_ATTEMPTS = 3;

    @Autowired private OrderConfirmedOutboxStateService stateService;
    @Autowired private OrderConfirmedOutboxJpaRepository repository;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private DatabaseCleanUp databaseCleanUp;

    private final ExecutorService otherRelay = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        otherRelay.shutdownNow();
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("다른 실행이 같은 행을 먼저 시작하면 이전 값을 읽은 실행은 시도를 시작하지 않는다.")
    @Test
    void skipsAttemptAlreadyStartedByAnotherRelay() {
        insertPending(CREATED_AT);

        OrderConfirmedOutboxStateService.BeginAttemptResult late = writeAfterOtherRelayCommits(
            () -> assertThat(stateService.beginAttempt(EVENT_ID, CREATED_AT, MAX_WAIT, MAX_ATTEMPTS).kind())
                .isEqualTo(OrderConfirmedOutboxStateService.BeginAttemptKind.STARTED),
            () -> stateService.beginAttempt(EVENT_ID, CREATED_AT, MAX_WAIT, MAX_ATTEMPTS));

        assertThat(late.kind()).isEqualTo(OrderConfirmedOutboxStateService.BeginAttemptKind.SKIPPED);
        assertThat(row()).isEqualTo(new RowState(
            "PENDING", 1, 1, utc(CREATED_AT.plus(MAX_WAIT)), null, utc(CREATED_AT)));
    }

    @DisplayName("다른 실행이 다음 시도를 시작한 뒤 도착한 이전 시도 결과는 상태를 덮어쓰지 않는다.")
    @Test
    void ignoresPreviousAttemptResultAfterAnotherRelayStartsNextAttempt() {
        insertPending(CREATED_AT);
        OrderConfirmedOutboxAttempt first = stateService.beginAttempt(
            EVENT_ID, CREATED_AT, MAX_WAIT, MAX_ATTEMPTS).attempt();
        ZonedDateTime nextDue = CREATED_AT.plus(MAX_WAIT);

        boolean published = writeAfterOtherRelayCommits(
            () -> assertThat(stateService.beginAttempt(EVENT_ID, nextDue, MAX_WAIT, MAX_ATTEMPTS).kind())
                .isEqualTo(OrderConfirmedOutboxStateService.BeginAttemptKind.STARTED),
            () -> stateService.markPublished(first, nextDue));

        assertThat(published).isFalse();
        assertThat(row()).isEqualTo(new RowState(
            "PENDING", 2, 2, utc(nextDue.plus(MAX_WAIT)), null, utc(nextDue)));
    }

    // 늦은 실행이 행을 먼저 읽고, 다른 실행이 갱신을 commit한 뒤, 늦은 실행이 같은 TX에서 갱신하는 순서를 만든다.
    private <T> T writeAfterOtherRelayCommits(Runnable otherRelayCommit, Callable<T> lateWrite) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            try {
                repository.findByEventId(EVENT_ID).orElseThrow();
                otherRelay.submit(otherRelayCommit).get(5, TimeUnit.SECONDS);
                return lateWrite.call();
            } catch (Exception failure) {
                throw new IllegalStateException(failure);
            }
        });
    }

    private void insertPending(ZonedDateTime nextAttemptAt) {
        LocalDateTime createdAt = utc(CREATED_AT.minusMinutes(1));
        jdbcTemplate.update("""
            INSERT INTO order_confirmed_outbox
              (event_id, order_id, event_type, schema_version, message_key, payload, status,
               attempt_count, cycle_attempt_count, next_attempt_at, created_at, updated_at)
            VALUES (?, 1, 'OrderConfirmed', 1, '1', '{}', 'PENDING', 0, 0, ?, ?, ?)
            """, EVENT_ID, utc(nextAttemptAt), createdAt, createdAt);
    }

    private RowState row() {
        return jdbcTemplate.queryForObject("""
            SELECT status, attempt_count, cycle_attempt_count, next_attempt_at, published_at, updated_at
            FROM order_confirmed_outbox
            WHERE event_id = ?
            """, (resultSet, rowNumber) -> new RowState(
            resultSet.getString(1), resultSet.getInt(2), resultSet.getInt(3),
            resultSet.getObject(4, LocalDateTime.class), resultSet.getObject(5, LocalDateTime.class),
            resultSet.getObject(6, LocalDateTime.class)), EVENT_ID);
    }

    private static LocalDateTime utc(ZonedDateTime time) {
        return time.withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
    }

    // 조건부 갱신은 @PreUpdate를 거치지 않으므로 updated_at도 상태 전이 시각으로 함께 확인한다.
    private record RowState(String status, int attemptCount, int cycleAttemptCount,
                            LocalDateTime nextAttemptAt, LocalDateTime publishedAt, LocalDateTime updatedAt) {}
}
