package com.loopers.infrastructure.outbox;

import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxRepository;
import com.loopers.utils.DatabaseCleanUp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.ZonedDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

@SpringBootTest
class OutboxRepositoryTest {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("Outbox 를 저장하고 조회할 때,")
    @Nested
    class SaveAndFind {

        @DisplayName("같은 eventId 는 두 번 저장할 수 없다.")
        @Test
        void rejectsSecondSave_whenEventIdIsSame() {
            // given
            ZonedDateTime recordedAt = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
            outboxRepository.save(
                Outbox.record("order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1}, recordedAt)
            );

            // when
            Outbox duplicated =
                Outbox.record("order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1}, recordedAt);

            // then
            assertThrows(DataIntegrityViolationException.class, () -> outboxRepository.save(duplicated));
        }
    }

    @DisplayName("전송 대상을 조회할 때,")
    @Nested
    class FindDueForDelivery {

        @DisplayName("PENDING 이고 다음 시도 시각이 지났거나 같은 건만, 기록한 순서대로 돌려준다.")
        @Test
        void returnsOnlyDuePendingInIdOrder() {
            // given
            ZonedDateTime now = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
            ZonedDateTime before = now.minusMinutes(1);
            ZonedDateTime after = now.plusMinutes(1);
            outboxRepository.save(Outbox.record("event-A", 1L, "OrderConfirmed", "v1", "1", new byte[] {1}, before));
            outboxRepository.save(Outbox.record("event-B", 2L, "OrderConfirmed", "v1", "2", new byte[] {1}, after));
            Outbox published = Outbox.record("event-C", 3L, "OrderConfirmed", "v1", "3", new byte[] {1}, before);
            published.markPublished(before);
            outboxRepository.save(published);
            outboxRepository.save(Outbox.record("event-D", 4L, "OrderConfirmed", "v1", "4", new byte[] {1}, before));
            outboxRepository.save(Outbox.record("event-E", 5L, "OrderConfirmed", "v1", "5", new byte[] {1}, now));

            // when
            List<Outbox> due = outboxRepository.findDueForDelivery(now, 10);

            // then
            assertThat(due).extracting(Outbox::getEventId).containsExactly("event-A", "event-D", "event-E");
        }

        @DisplayName("한 번에 limit 개까지만, 기록한 순서대로 앞에서부터 돌려준다.")
        @Test
        void returnsAtMostLimit_inIdOrder() {
            // given
            ZonedDateTime now = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
            ZonedDateTime before = now.minusMinutes(1);
            outboxRepository.save(Outbox.record("event-A", 1L, "OrderConfirmed", "v1", "1", new byte[] {1}, before));
            outboxRepository.save(Outbox.record("event-B", 2L, "OrderConfirmed", "v1", "2", new byte[] {1}, before));
            outboxRepository.save(Outbox.record("event-C", 3L, "OrderConfirmed", "v1", "3", new byte[] {1}, before));

            // when
            List<Outbox> due = outboxRepository.findDueForDelivery(now, 2);

            // then
            assertThat(due).extracting(Outbox::getEventId).containsExactly("event-A", "event-B");
        }
    }
}
