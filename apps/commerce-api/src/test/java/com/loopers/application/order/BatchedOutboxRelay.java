package com.loopers.application.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 실험용 후보 relay. 제품 코드가 아니다.
 * <p>
 * 회차의 시도 기록과 PUBLISHED 기록을 UPDATE 한 번씩으로 묶고, 보내기는 모두 보낸 뒤 응답을 모아 기다린다.
 * 실패한 행만 제품과 같은 {@link OrderOutboxTransactions#scheduleRetry}로 재시도 시각과 오류를 남긴다.
 * 지금 relay와 달리 실패한 행 뒤의 행도 같은 회차에 보낸다. 주문 확정 사건은 주문마다 하나라 행 사이의 순서는 따지지 않는다.
 */
final class BatchedOutboxRelay {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate namedJdbc;
    private final TransactionTemplate transaction;
    private final KafkaTemplate<Object, Object> kafka;
    private final OrderOutboxTransactions transactions;
    private final String topic;
    private final int batchSize;
    private final Duration sendWait;
    private final Duration firstRetry;
    private final Duration maxRetry;
    /** 보내기가 끝난 뒤 PUBLISHED를 적기 전에 실행한다. 이 사이에 relay가 멈추는 경우를 흉내 낼 때 쓴다. */
    private final Runnable afterSends;

    BatchedOutboxRelay(JdbcTemplate jdbc, NamedParameterJdbcTemplate namedJdbc, TransactionTemplate transaction,
                       KafkaTemplate<Object, Object> kafka, OrderOutboxTransactions transactions, String topic,
                       int batchSize, Duration sendWait, Duration firstRetry, Duration maxRetry, Runnable afterSends) {
        this.jdbc = jdbc;
        this.namedJdbc = namedJdbc;
        this.transaction = transaction;
        this.kafka = kafka;
        this.transactions = transactions;
        this.topic = topic;
        this.batchSize = batchSize;
        this.sendWait = sendWait;
        this.firstRetry = firstRetry;
        this.maxRetry = maxRetry;
        this.afterSends = afterSends;
    }

    private record Row(long id, String eventId, String messageKey, String payload) {}

    /** 한 회차를 실행하고 PUBLISHED로 저장한 건수를 돌려준다. */
    int runOnce() {
        List<Row> rows = claimDueRows();
        if (rows.isEmpty()) {
            return 0;
        }
        List<CompletableFuture<?>> sends = rows.stream().map(this::send).toList();

        List<Long> published = new ArrayList<>();
        Map<Long, String> failed = new LinkedHashMap<>();
        long deadline = System.nanoTime() + sendWait.toNanos();
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            try {
                sends.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                published.add(row.id());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                failed.put(row.id(), error(row, exception));
            } catch (ExecutionException | TimeoutException exception) {
                failed.put(row.id(), error(row, exception));
            }
        }

        afterSends.run();
        if (!published.isEmpty()) {
            Timestamp now = Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS));
            namedJdbc.update("""
                UPDATE order_outbox SET status = 'PUBLISHED', published_at = :now, last_error = NULL, updated_at = :now
                WHERE id IN (:ids)""", new MapSqlParameterSource("ids", published).addValue("now", now));
        }
        failed.forEach((id, error) -> transactions.scheduleRetry(id, error, firstRetry, maxRetry));
        return published.size();
    }

    /** 회차의 대상 행을 읽고, 시도 기록을 UPDATE 한 번으로 남긴다. */
    private List<Row> claimDueRows() {
        return transaction.execute(status -> {
            List<Row> rows = jdbc.query("""
                    SELECT id, event_id, message_key, payload FROM order_outbox
                    WHERE status = 'PENDING' AND next_attempt_at <= ? ORDER BY id LIMIT ?""",
                (result, rowNumber) -> new Row(result.getLong("id"), result.getString("event_id"),
                    result.getString("message_key"), result.getString("payload")),
                Timestamp.from(Instant.now()), batchSize);
            if (!rows.isEmpty()) {
                namedJdbc.update("UPDATE order_outbox SET attempt_count = attempt_count + 1, updated_at = :now WHERE id IN (:ids)",
                    new MapSqlParameterSource("ids", rows.stream().map(Row::id).toList())
                        .addValue("now", Timestamp.from(Instant.now().truncatedTo(ChronoUnit.MICROS))));
            }
            return rows;
        });
    }

    /** send() 호출에서 바로 던진 실패(크기 초과 등)도 실패한 응답으로 모은다. */
    private CompletableFuture<?> send(Row row) {
        try {
            return kafka.send(topic, row.messageKey(), row.payload());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** 제품 publisher와 같은 형식으로 eventId와 가장 안쪽 원인을 남긴다. */
    private static String error(Row row, Exception exception) {
        Throwable cause = exception;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return "broker 확인 실패: eventId=%s, %s: %s".formatted(row.eventId(), cause.getClass().getSimpleName(),
            cause.getMessage());
    }
}
