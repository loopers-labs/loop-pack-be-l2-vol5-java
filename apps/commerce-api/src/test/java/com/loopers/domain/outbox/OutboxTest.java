package com.loopers.domain.outbox;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OutboxTest {

    @DisplayName("Outbox 를 기록할 때,")
    @Nested
    class Record {

        @DisplayName("PENDING 이고, 시도 0회이며, 기록 시각에 바로 전송 대상이 된다.")
        @Test
        void startsPending_whenRecorded() {
            // given
            ZonedDateTime recordedAt = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");

            // when
            Outbox outbox = Outbox.record(
                "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3}, recordedAt
            );

            // then
            assertThat(outbox.getEventId()).isEqualTo("order-confirmed:v1:42");
            assertThat(outbox.getOrderId()).isEqualTo(42L);
            assertThat(outbox.getMessageKey()).isEqualTo("42");
            assertThat(outbox.getPayload()).containsExactly(1, 2, 3);
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(outbox.getAttemptCount()).isZero();
            assertThat(outbox.getNextAttemptAt()).isEqualTo(recordedAt);
            assertThat(outbox.getPublishedAt()).isNull();
            assertThat(outbox.getLastError()).isNull();
        }

        @DisplayName("eventId·orderId·payload 중 하나라도 없거나 비어 있으면 INVALID_INPUT 으로 거절한다.")
        @ParameterizedTest(name = "eventId={0}, orderId={1}, payload={2}")
        @CsvSource(nullValues = "NULL", value = {
            "NULL,                  42,   payload",
            "'',                    42,   payload",
            "'  ',                  42,   payload",
            "order-confirmed:v1:42, NULL, payload",
            "order-confirmed:v1:42, 42,   NULL",
            "order-confirmed:v1:42, 42,   ''"
        })
        void throwsInvalidInput_whenRequiredValueIsMissing(String eventId, Long orderId, String payloadText) {
            // given
            byte[] payload = payloadText == null ? null : payloadText.getBytes();
            ZonedDateTime recordedAt = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");

            // when
            CoreException exception = assertThrows(CoreException.class, () ->
                Outbox.record(eventId, orderId, "OrderConfirmed", "v1", "42", payload, recordedAt)
            );

            // then
            assertThat(exception.getErrorType()).isEqualTo(ErrorType.INVALID_INPUT);
        }
    }

    @DisplayName("전송 결과를 기록할 때,")
    @Nested
    class RecordDelivery {

        @DisplayName("broker 성공을 저장하면 PUBLISHED 가 되고 저장 시각이 남는다. 시도 횟수는 시작할 때 올린 1이다.")
        @Test
        void becomesPublished_whenPublishedMarked() {
            // given
            ZonedDateTime recordedAt = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
            ZonedDateTime publishedAt = ZonedDateTime.parse("2026-11-04T10:00:05+09:00[Asia/Seoul]");
            Outbox outbox = Outbox.record(
                "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3}, recordedAt
            );
            outbox.startAttempt();

            // when
            outbox.markPublished(publishedAt);

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(outbox.getPublishedAt()).isEqualTo(publishedAt);
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
        }

        @DisplayName("전송이 실패하면 PENDING 으로 남고, 다음 시도 시각과 오류가 기록된다.")
        @Test
        void staysPending_whenRetryScheduled() {
            // given
            ZonedDateTime recordedAt = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
            ZonedDateTime nextAttemptAt = ZonedDateTime.parse("2026-11-04T10:00:30+09:00[Asia/Seoul]");
            Outbox outbox = Outbox.record(
                "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3}, recordedAt
            );
            outbox.startAttempt();

            // when
            outbox.scheduleRetry(nextAttemptAt, "send timeout");

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(outbox.getNextAttemptAt()).isEqualTo(nextAttemptAt);
            assertThat(outbox.getLastError()).isEqualTo("send timeout");
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
            assertThat(outbox.getPublishedAt()).isNull();
        }

        @DisplayName("이미 PUBLISHED 인 뒤에 늦은 실패가 기록돼도 상태·시각·오류는 바뀌지 않는다.")
        @Test
        void staysPublished_whenRetryScheduledAfterPublished() {
            // given
            ZonedDateTime recordedAt = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
            ZonedDateTime publishedAt = ZonedDateTime.parse("2026-11-04T10:00:05+09:00[Asia/Seoul]");
            Outbox outbox = Outbox.record(
                "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3}, recordedAt
            );
            outbox.startAttempt();
            outbox.markPublished(publishedAt);

            // when
            outbox.scheduleRetry(ZonedDateTime.parse("2026-11-04T10:01:00+09:00[Asia/Seoul]"), "late failure");

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(outbox.getPublishedAt()).isEqualTo(publishedAt);
            assertThat(outbox.getNextAttemptAt()).isEqualTo(recordedAt);
            assertThat(outbox.getLastError()).isNull();
        }

        @DisplayName("다시 보내도 거절될 메시지면 BLOCKED 로 격리되고, 원인이 남는다.")
        @Test
        void becomesBlocked_whenRejected() {
            // given
            ZonedDateTime recordedAt = ZonedDateTime.parse("2026-11-04T10:00:00+09:00[Asia/Seoul]");
            Outbox outbox = Outbox.record(
                "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", new byte[] {1, 2, 3}, recordedAt
            );
            outbox.startAttempt();

            // when
            outbox.block("record too large");

            // then
            assertThat(outbox.getStatus()).isEqualTo(OutboxStatus.BLOCKED);
            assertThat(outbox.getLastError()).isEqualTo("record too large");
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
            assertThat(outbox.getPublishedAt()).isNull();
        }
    }
}
