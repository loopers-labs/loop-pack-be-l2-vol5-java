package com.loopers.domains.order.in;

import com.loopers.domains.order.app.OrderOutboxResume;
import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.out.OrderConfirmedMessageSerializer;
import com.loopers.support.kafka.OrderEventsProbe;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;

import static org.assertj.core.api.Assertions.assertThat;

// 운영 경계: 사람이 원인을 고친 뒤 BLOCKED 행 하나를 로컬 진입점으로 다시 보낼 차례로 돌린다. 재개도 조건부 갱신이라 BLOCKED가 아닌 행은 바꾸지 않는다.
class OrderOutboxResumeIntegrationTest extends OrderEventsIntegrationSupport {
    private static final Instant BLOCKED_AT = NOW.minusSeconds(3600);

    @Autowired private OrderOutboxResume orderOutboxResume;
    @Autowired private ApplicationContext context;

    @Test
    @DisplayName("BLOCKED 행을 재개하면 PENDING·행 탓 실패 0·다음 시도 지금이 되고 시도 수·마지막 오류는 남으며, 다음 회차에 같은 행·같은 eventId로 PUBLISHED가 된다")
    void resumedBlockedRowIsPublishedOnNextRound() {
        long id = insertRow(701, "BLOCKED", 5, 5, "RecordTooLargeException: too large", BLOCKED_AT, null);

        OrderOutboxResume.Result result = orderOutboxResume.resume(eventId(701));

        assertThat(result).isEqualTo(OrderOutboxResume.Result.RESUMED);
        assertThat(row(id))
            .containsEntry("status", "PENDING")
            .containsEntry("row_failure_count", 0)
            .containsEntry("next_attempt_at", local(NOW))
            .containsEntry("attempt_count", 5)
            .containsEntry("last_error", "RecordTooLargeException: too large");

        relay.runOnce();

        assertThat(row(id)).containsEntry("status", "PUBLISHED").containsEntry("published_at", local(NOW));
        List<ConsumerRecord<String, String>> records = probe.readThrough(from).stream()
            .filter(record -> eventId(701).equals(OrderEventsProbe.eventId(record)))
            .toList();
        assertThat(records).hasSize(1);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox where event_id = ?", Long.class, eventId(701))).isEqualTo(1L);
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"PENDING", "PUBLISHED"})
    @DisplayName("BLOCKED가 아닌 행을 재개하면 NOT_BLOCKED를 돌려주고 행의 모든 값이 그대로다")
    void notBlockedRowIsUnchanged(String status) {
        Instant publishedAt = "PUBLISHED".equals(status) ? BLOCKED_AT : null;
        long id = insertRow(702, status, 2, 1, "TimeoutException: x", BLOCKED_AT, publishedAt);
        Map<String, Object> before = row(id);

        OrderOutboxResume.Result result = orderOutboxResume.resume(eventId(702));

        assertThat(result).isEqualTo(OrderOutboxResume.Result.NOT_BLOCKED);
        assertThat(row(id)).isEqualTo(before);
    }

    @Test
    @DisplayName("없는 eventId를 재개하면 NOT_BLOCKED를 돌려주고 Outbox 행이 생기지 않는다")
    void missingEventIdIsNotBlocked() {
        OrderOutboxResume.Result result = orderOutboxResume.resume(eventId(703));

        assertThat(result).isEqualTo(OrderOutboxResume.Result.NOT_BLOCKED);
        assertThat(jdbc.queryForObject("select count(*) from order_outbox", Long.class)).isZero();
    }

    @Test
    @DisplayName("로컬 재개 러너는 local 프로필에서만 등록되어 test 프로필 컨텍스트에는 없다")
    void localToolsAreNotRegisteredOutsideLocalProfile() {
        assertThat(context.getBeansOfType(OrderOutboxLocalTools.class)).isEmpty();
    }

    private long insertRow(long orderId, String status, int attemptCount, int rowFailureCount, String lastError,
        Instant nextAttemptAt, Instant publishedAt) {
        OrderConfirmedPayload payload = new OrderConfirmedPayload(String.valueOf(orderId), eventId(orderId), orderId, NOW,
            List.of(new OrderConfirmedPayload.Item(1, 1)));
        jdbc.update("insert into order_outbox(event_id, order_id, event_type, schema_version, message_key, payload, status,"
                + " attempt_count, row_failure_count, last_error, next_attempt_at, created_at, published_at)"
                + " values (?, ?, 'OrderConfirmed', 1, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            payload.eventId(), orderId, payload.key(), OrderConfirmedMessageSerializer.serialize(payload), status, attemptCount,
            rowFailureCount, lastError, local(nextAttemptAt), local(BLOCKED_AT), publishedAt == null ? null : local(publishedAt));
        return jdbc.queryForObject("select id from order_outbox where order_id = ?", Long.class, orderId);
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select event_id, status, attempt_count, row_failure_count, last_error, next_attempt_at, published_at,"
            + " message_key, payload, created_at from order_outbox where id = ?", id);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
