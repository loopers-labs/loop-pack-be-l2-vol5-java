package com.loopers.domains.order.in;

import com.loopers.domains.order.app.port.OrderConfirmedPayload;
import com.loopers.domains.order.app.port.SendOutcome;
import com.loopers.domains.order.out.OrderConfirmedMessageSerializer;
import com.loopers.domains.order.out.OrderConfirmedOutboxPersistenceAdapter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

// 7주차 2단계 relay(D112~D117). 스케줄은 끄고 runOnce()를 직접 부른다. 결과는 JDBC 새 조회로, broker는 TopicReader로 본다.
// 실패 분류 장면은 전송 포트 대역(ScriptedMessageSender)으로 결과를 지정한다. 실제 broker 장애·동시 실행은 증명하지 못한다(3단계, D116).
class OrderOutboxRelayIntegrationTest extends OrderEventsIntegrationSupport {
    @Autowired private OrderConfirmedOutboxPersistenceAdapter outboxAdapter;

    @Test
    @DisplayName("R1: 차례가 된 PENDING 1행을 한 회차에 보내면 broker record의 key·value가 저장된 message_key·payload와 같고 행은 PUBLISHED·published_at은 주입 시계다")
    void publishesStoredMessageAsIs() {
        long id = insertPending(501, NOW);
        Map<String, Object> stored = row(id);

        relay.runOnce();

        assertThat(row(id))
            .containsEntry("status", "PUBLISHED")
            .containsEntry("published_at", local(NOW))
            .containsEntry("attempt_count", 0);
        ConsumerRecord<String, String> record = probe.awaitEvent(from, eventId(501));
        assertThat(record.key()).isEqualTo(stored.get("message_key"));
        assertThat(record.value()).isEqualTo(stored.get("payload"));
    }

    @Test
    @DisplayName("R2: 보낼 행이 없으면 회차는 예외 없이 끝나고 send를 부르지 않는다")
    void emptyRoundSendsNothing() {
        assertThatCode(() -> relay.runOnce()).doesNotThrowAnyException();

        assertThat(sender.sentKeys()).isEmpty();
    }

    @Test
    @DisplayName("R3: 다음 시도 시각이 아직 오지 않은 행은 보내지 않고, 시계가 그 시각에 닿은 뒤의 회차에서 보낸다")
    void notDueRowWaitsForItsTime() {
        long id = insertPending(502, NOW.plusSeconds(1));

        relay.runOnce();

        assertThat(sender.sentKeys()).isEmpty();
        assertThat(row(id)).containsEntry("status", "PENDING");
        clock.advance(Duration.ofSeconds(1));
        relay.runOnce();
        assertThat(row(id)).containsEntry("status", "PUBLISHED");
    }

    @Test
    @DisplayName("R4: 101행이면 묶음 크기 100으로 조회해 같은 회차에서 다음 묶음 1건까지 모두 보내고 순서는 next_attempt_at, 같은 시각이면 id다")
    void fullBatchContinuesInSameRoundInOrder() {
        sender.publishWithoutBroker();
        List<long[]> rows = new ArrayList<long[]>();
        // 둘씩(마지막은 하나) 같은 next_attempt_at을 갖게 해, 같은 시각 안에서는 id 순인지 함께 본다. id 순과 시각 순은 서로 섞인다.
        for (int index = 0; index <= 100; index++) {
            long orderId = 1000 + index;
            long secondsBefore = ((index * 37L) % 101) / 2;
            rows.add(new long[] {insertPending(orderId, NOW.minusSeconds(secondsBefore)), orderId, secondsBefore});
        }
        List<String> expected = rows.stream()
            .sorted(Comparator.<long[]>comparingLong(r -> -r[2]).thenComparingLong(r -> r[0]))
            .map(r -> String.valueOf(r[1]))
            .toList();

        relay.runOnce();

        assertThat(sender.sentKeys()).containsExactlyElementsOf(expected);
        assertThat(count("PUBLISHED")).isEqualTo(101);
        assertThat(failingOutbox.findDueLimits()).containsExactly(100, 100);
    }

    @Test
    @DisplayName("R12: send 중 인터럽트를 받아 인터럽트 플래그가 선 채로 돌아오면 relay는 그 플래그를 지우지 않고 그 행을 기록하지 않은 채(PENDING) 회차를 멈춰 다음 행을 보내지 않는다")
    void interruptStopsRoundAndKeepsFlag() {
        long interrupted = insertPending(1101, NOW.minusSeconds(2));
        long next = insertPending(1102, NOW.minusSeconds(1));
        sender.publishWithoutBroker();
        sender.onSend(() -> Thread.currentThread().interrupt());
        boolean interruptedAfterRound;
        try {
            relay.runOnce();
        } finally {
            interruptedAfterRound = Thread.interrupted();
        }

        assertThat(interruptedAfterRound).isTrue();
        assertThat(row(interrupted)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
        assertThat(sender.sendCount("1102")).isZero();
        assertThat(row(next)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
    }

    @Test
    @DisplayName("R5: 첫 행이 broker 수준으로 실패하면 회차를 멈춘다. 첫 행은 시도 1·행 탓 0·다음 시도 1초 뒤·오류 기록이고 둘째 행은 보내지 않는다")
    void brokerFailureStopsRound() {
        long first = insertPending(601, NOW.minusSeconds(2));
        long second = insertPending(602, NOW.minusSeconds(1));
        sender.script("601", new SendOutcome.BrokerFailure("broker 연결 실패"));

        relay.runOnce();

        assertThat(row(first))
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 1)
            .containsEntry("row_failure_count", 0)
            .containsEntry("next_attempt_at", local(NOW.plusSeconds(1)))
            .containsEntry("last_error", "broker 연결 실패");
        assertThat(sender.sendCount("602")).isZero();
        assertThat(row(second)).containsEntry("status", "PENDING").containsEntry("attempt_count", 0);
    }

    @Test
    @DisplayName("R6: broker 수준 실패가 10회 이어지면 간격이 1·2·4·8·16·32·60·60·60·60초로 늘고 행은 BLOCKED가 되지 않으며 행 탓 실패는 0이다")
    void longBrokerFailureBacksOffWithoutBlocking() {
        long id = insertPending(603, NOW);
        sender.always("603", new SendOutcome.BrokerFailure("broker 연결 실패"));
        List<Long> gaps = new ArrayList<Long>();

        for (int round = 0; round < 10; round++) {
            Instant before = clock.instant();
            relay.runOnce();
            Instant next = instant(row(id).get("next_attempt_at"));
            gaps.add(Duration.between(before, next).toSeconds());
            clock.set(next);
        }

        assertThat(gaps).containsExactly(1L, 2L, 4L, 8L, 16L, 32L, 60L, 60L, 60L, 60L);
        assertThat(row(id))
            .containsEntry("status", "PENDING")
            .containsEntry("row_failure_count", 0)
            .containsEntry("attempt_count", 10);
    }

    @Test
    @DisplayName("R7: 행 수준 실패면 그 행만 시도·행 탓 실패를 1 올리고 다음 행은 보내며, 같은 행이 5번째 실패하면 BLOCKED가 되어 다시 고르지 않는다")
    void rowFailureSkipsOnlyThatRowAndBlocksAtFive() {
        long bad = insertPending(701, NOW.minusSeconds(2));
        long good = insertPending(702, NOW.minusSeconds(1));
        sender.publishWithoutBroker();
        sender.always("701", new SendOutcome.RowFailure("메시지 너무 큼"));

        relay.runOnce();

        assertThat(row(bad))
            .containsEntry("status", "PENDING")
            .containsEntry("attempt_count", 1)
            .containsEntry("row_failure_count", 1)
            .containsEntry("last_error", "메시지 너무 큼");
        assertThat(row(good)).containsEntry("status", "PUBLISHED");
        for (int round = 2; round <= 5; round++) {
            clock.set(instant(row(bad).get("next_attempt_at")));
            relay.runOnce();
        }
        assertThat(row(bad))
            .containsEntry("status", "BLOCKED")
            .containsEntry("attempt_count", 5)
            .containsEntry("row_failure_count", 5);
        clock.advance(Duration.ofHours(1));
        relay.runOnce();
        assertThat(sender.sendCount("701")).isEqualTo(5);
    }

    @Test
    @DisplayName("R8: 상태 기록은 PENDING 행에만 적용된다. PUBLISHED 행에 broker 실패 기록, BLOCKED 행에 PUBLISHED 기록을 시도하면 영향 0행이고 상태가 그대로다")
    void stateUpdatesApplyOnlyToPending() {
        long published = insertPending(801, NOW);
        jdbc.update("update order_outbox set status = 'PUBLISHED', published_at = ? where id = ?", local(NOW), published);
        long blocked = insertPending(802, NOW);
        jdbc.update("update order_outbox set status = 'BLOCKED' where id = ?", blocked);

        int publishedAffected = outboxAdapter.markBrokerFailure(published, NOW.plusSeconds(1), "늦게 온 실패");
        int blockedAffected = outboxAdapter.markPublished(blocked, NOW.plusSeconds(1));

        assertThat(publishedAffected).isZero();
        assertThat(blockedAffected).isZero();
        assertThat(row(published)).containsEntry("status", "PUBLISHED").containsEntry("published_at", local(NOW))
            .containsEntry("attempt_count", 0);
        assertThat(row(blocked)).containsEntry("status", "BLOCKED").containsEntry("published_at", null);
    }

    @Test
    @DisplayName("S1: 설정 묶음 크기 3에 7행이면 한 회차에서 3·3·1건으로 세 번 조회해 7행 모두 보낸다")
    void fullBatchesContinueUntilPartial() {
        settings(3, 10_000, true);
        sender.publishWithoutBroker();
        for (int index = 0; index < 7; index++) {
            insertPending(900 + index, NOW.minusSeconds(10 - index));
        }

        relay.runOnce();

        assertThat(count("PUBLISHED")).isEqualTo(7);
        assertThat(failingOutbox.findDueLimits()).containsExactly(3, 3, 3);
    }

    @Test
    @DisplayName("S2: 묶음 2·회차 최대 1초에서 보낼 때마다 400ms가 지나면 둘째 묶음 뒤 1초를 넘어 다음 묶음을 시작하지 않는다(4건 PUBLISHED, 6건 PENDING)")
    void roundStopsAfterMaxDuration() {
        settings(2, 1_000, true);
        sender.publishWithoutBroker();
        sender.onSend(() -> clock.advance(Duration.ofMillis(400)));
        for (int index = 0; index < 10; index++) {
            insertPending(950 + index, NOW.minusSeconds(20 - index));
        }

        relay.runOnce();

        assertThat(count("PUBLISHED")).isEqualTo(4);
        assertThat(count("PENDING")).isEqualTo(6);
    }

    @Test
    @DisplayName("S3: 설정 행의 묶음 크기를 SQL로 2에서 5로 바꾸면 다음 회차의 조회 크기가 5가 된다")
    void batchSizeChangeAppliesNextRound() {
        settings(2, 10_000, true);
        sender.publishWithoutBroker();
        for (int index = 0; index < 5; index++) {
            insertPending(970 + index, NOW.minusSeconds(10 - index));
        }
        relay.runOnce();
        assertThat(failingOutbox.findDueLimits()).first().isEqualTo(2);
        failingOutbox.disarm();
        jdbc.update("update outbox_relay_settings set batch_size = 5 where id = 1");
        for (int index = 0; index < 5; index++) {
            insertPending(980 + index, NOW.minusSeconds(5 - index));
        }

        relay.runOnce();

        assertThat(failingOutbox.findDueLimits()).first().isEqualTo(5);
        assertThat(count("PUBLISHED")).isEqualTo(10);
    }

    @Test
    @DisplayName("S4: 설정 행의 묶음 크기가 범위 밖(0)이면 기본 묶음 100으로 조회해 행을 보낸다")
    void outOfRangeSettingsUseDefault() {
        settings(0, 10_000, true);
        sender.publishWithoutBroker();
        insertPending(990, NOW);

        relay.runOnce();

        assertThat(failingOutbox.findDueLimits()).first().isEqualTo(100);
        assertThat(count("PUBLISHED")).isEqualTo(1);
    }

    @Test
    @DisplayName("S5: 설정 행이 꺼져 있으면 회차는 보내지 않고 PENDING이 남으며, 다시 켜면 다음 회차에서 broker로 보낸다")
    void disabledSettingsSkipRoundUntilEnabled() {
        settings(100, 10_000, false);
        long id = insertPending(995, NOW);

        relay.runOnce();

        assertThat(sender.sentKeys()).isEmpty();
        assertThat(row(id)).containsEntry("status", "PENDING");
        assertThat(eventIdsOnBroker()).doesNotContain(eventId(995));
        jdbc.update("update outbox_relay_settings set enabled = true where id = 1");
        relay.runOnce();
        assertThat(row(id)).containsEntry("status", "PUBLISHED");
        assertThat(probe.awaitEvent(from, eventId(995)).key()).isEqualTo("995");
    }

    private long insertPending(long orderId, Instant nextAttemptAt) {
        OrderConfirmedPayload payload = new OrderConfirmedPayload(String.valueOf(orderId), eventId(orderId), orderId, NOW,
            List.of(new OrderConfirmedPayload.Item(1, 1)));
        jdbc.update("insert into order_outbox(event_id, order_id, event_type, schema_version, message_key, payload, status,"
                + " attempt_count, row_failure_count, next_attempt_at, created_at) values (?, ?, 'OrderConfirmed', 1, ?, ?,"
                + " 'PENDING', 0, 0, ?, ?)",
            payload.eventId(), orderId, payload.key(), OrderConfirmedMessageSerializer.serialize(payload),
            local(nextAttemptAt), local(NOW));
        return jdbc.queryForObject("select id from order_outbox where order_id = ?", Long.class, orderId);
    }

    private void settings(int batchSize, int maxRoundMillis, boolean enabled) {
        jdbc.update("insert into outbox_relay_settings(id, batch_size, max_round_millis, enabled, updated_at) values (1, ?, ?, ?, ?)",
            batchSize, maxRoundMillis, enabled, local(NOW));
    }

    private Map<String, Object> row(long id) {
        return jdbc.queryForMap("select status, attempt_count, row_failure_count, last_error, next_attempt_at, published_at,"
            + " message_key, payload from order_outbox where id = ?", id);
    }

    private long count(String status) {
        return jdbc.queryForObject("select count(*) from order_outbox where status = ?", Long.class, status);
    }

    private static LocalDateTime local(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(Object value) {
        return ((LocalDateTime) value).toInstant(ZoneOffset.UTC);
    }
}
