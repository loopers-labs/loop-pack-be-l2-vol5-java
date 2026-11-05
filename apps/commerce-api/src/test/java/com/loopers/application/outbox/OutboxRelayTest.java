package com.loopers.application.outbox;

import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxService;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class OutboxRelayTest {

    // 고정 시각 2026-11-04 10:00:00 (Asia/Seoul)
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-04T01:00:00Z"), ZoneId.of("Asia/Seoul"));
    private static final ZonedDateTime DUE_SINCE = ZonedDateTime.parse("2026-11-04T09:59:00+09:00[Asia/Seoul]");

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("전송에 성공하면 PUBLISHED 와 저장 시각이 기록된다.")
    @Test
    void marksPublished_whenPublishSucceeds() {
        // given
        Outbox saved = outboxService.save(
            Outbox.record("event-A", 1L, "OrderConfirmed", "v1", "1", new byte[] {1}, DUE_SINCE)
        );
        OutboxPublisher publisher = outbox -> { };
        OutboxRelay relay = new OutboxRelay(outboxService, publisher, transactionManager, CLOCK, 20, Duration.ofSeconds(30));

        // when
        relay.relayOnce();

        // then
        Outbox found = outboxService.get(saved.getId());
        assertThat(found.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(found.getPublishedAt().toInstant()).isEqualTo(Instant.parse("2026-11-04T01:00:00Z"));
        assertThat(found.getAttemptCount()).isEqualTo(1);
    }

    @DisplayName("전송에 실패하면 PENDING 으로 남고, 재시도 간격 뒤의 시각과 오류가 기록된다.")
    @Test
    void keepsPendingWithNextAttempt_whenPublishFails() {
        // given
        Outbox saved = outboxService.save(
            Outbox.record("event-A", 1L, "OrderConfirmed", "v1", "1", new byte[] {1}, DUE_SINCE)
        );
        OutboxPublisher publisher = outbox -> {
            throw new OutboxPublishException("대기 시간 초과: timeout", new RuntimeException());
        };
        OutboxRelay relay = new OutboxRelay(outboxService, publisher, transactionManager, CLOCK, 20, Duration.ofSeconds(30));

        // when
        relay.relayOnce();

        // then
        Outbox found = outboxService.get(saved.getId());
        assertThat(found.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(found.getAttemptCount()).isEqualTo(1);
        assertThat(found.getNextAttemptAt().toInstant()).isEqualTo(Instant.parse("2026-11-04T01:00:30Z"));
        assertThat(found.getLastError()).contains("timeout");
        assertThat(found.getPublishedAt()).isNull();
    }

    @DisplayName("한 건이 실패해도 같은 회차의 다음 건은 계속 전송한다.")
    @Test
    void continuesWithNext_whenOneFails() {
        // given
        Outbox failing = outboxService.save(
            Outbox.record("event-A", 1L, "OrderConfirmed", "v1", "1", new byte[] {1}, DUE_SINCE)
        );
        Outbox healthy = outboxService.save(
            Outbox.record("event-B", 2L, "OrderConfirmed", "v1", "2", new byte[] {1}, DUE_SINCE)
        );
        OutboxPublisher publisher = outbox -> {
            if (outbox.getEventId().equals("event-A")) {
                throw new OutboxPublishException("broker down", new RuntimeException());
            }
        };
        OutboxRelay relay = new OutboxRelay(outboxService, publisher, transactionManager, CLOCK, 20, Duration.ofSeconds(30));

        // when
        relay.relayOnce();

        // then
        assertThat(outboxService.get(failing.getId()).getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(outboxService.get(healthy.getId()).getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
    }

    @DisplayName("전송은 DB 트랜잭션 밖에서 한다.")
    @Test
    void publishesOutsideTransaction() {
        // given
        outboxService.save(Outbox.record("event-A", 1L, "OrderConfirmed", "v1", "1", new byte[] {1}, DUE_SINCE));
        List<Boolean> transactionActiveDuringPublish = new ArrayList<>();
        OutboxPublisher publisher = outbox ->
            transactionActiveDuringPublish.add(TransactionSynchronizationManager.isActualTransactionActive());
        OutboxRelay relay = new OutboxRelay(outboxService, publisher, transactionManager, CLOCK, 20, Duration.ofSeconds(30));

        // when
        relay.relayOnce();

        // then
        assertThat(transactionActiveDuringPublish).containsExactly(false);
    }

    @DisplayName("전송할 수 없는 상태(회로 열림)면 회차를 건너뛴다. 대상의 시도 횟수를 올리지 않고 보내지도 않는다.")
    @Test
    void skipsRound_whenPublisherUnavailable() {
        // given: 전송할 차례가 된 Outbox 와, 지금은 전송할 수 없다고 답하는 publisher
        Outbox saved = outboxService.save(
            Outbox.record("event-A", 1L, "OrderConfirmed", "v1", "1", new byte[] {1}, DUE_SINCE)
        );
        List<String> publishedEventIds = new ArrayList<>();
        OutboxPublisher unavailablePublisher = new OutboxPublisher() {
            @Override
            public void publish(Outbox outbox) {
                publishedEventIds.add(outbox.getEventId());
            }

            @Override
            public boolean canPublish() {
                return false;
            }
        };
        OutboxRelay relay = new OutboxRelay(
            outboxService, unavailablePublisher, transactionManager, CLOCK, 20, Duration.ofSeconds(30)
        );

        // when
        relay.relayOnce();

        // then
        assertThat(publishedEventIds).isEmpty();
        Outbox found = outboxService.get(saved.getId());
        assertThat(found.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(found.getAttemptCount()).isZero();
    }
}
