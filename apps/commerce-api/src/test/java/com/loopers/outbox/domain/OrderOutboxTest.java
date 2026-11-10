package com.loopers.outbox.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 주문 Outbox 상태 전이 (docs/week7/plan.md "핵심 1"). 시각은 모두 고정한 리터럴이다.
 * 거절 사례는 예외(CONFLICT)와 함께 "아무 값도 바뀌지 않았다"를 전이 전 스냅샷과 비교해 확인한다.
 * <p>
 * <b>lease = SENDING 표시의 유효 기한.</b> 이 시각까지 끝나지 않으면 relay가 멈춘 것으로 보고 다시 보낸다.
 * SENDING일 때의 {@code next_attempt_at}이고, 맡을 때 {@code now + OrderOutbox.LEASE(1분)}로 정한다.
 * 실패 뒤의 재시도 시각(backoff)과 같은 컬럼을 쓰지만 원인이 다르다 — 그쪽은 "실패라는 답을 받았을 때", lease는 "답을 못 적고 멈췄을 때".
 */
class OrderOutboxTest {

    private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 11, 4, 10, 0, 0, 0, ZoneId.of("Asia/Seoul"));
    private static final Duration DELAY = Duration.ofSeconds(10);

    private static OrderOutbox pending() {
        return OrderOutbox.pending("e-1", 1L, "OrderConfirmed", 1, "{}", T0);
    }

    /** T0에 맡았다 — lease 기한은 T0 + 1분 */
    private static OrderOutbox sending() {
        OrderOutbox outbox = pending();
        outbox.startSending(T0);
        return outbox;
    }

    private static OrderOutbox published() {
        OrderOutbox outbox = sending();
        outbox.markPublished(2, 7L, T0.plusSeconds(1));
        return outbox;
    }

    /** 맡기 → 실패를 3번. 실패마다 DELAY 뒤가 다음 차례다 */
    private static OrderOutbox blocked() {
        OrderOutbox outbox = pending();
        ZonedDateTime at = T0;
        for (int i = 1; i <= OrderOutbox.MAX_ATTEMPTS; i++) {
            outbox.startSending(at);
            outbox.recordFailure("broker down " + i, at.plusSeconds(1), DELAY);
            at = at.plusSeconds(1).plus(DELAY);
        }
        return outbox;
    }

    private record Snapshot(
        OrderOutboxStatus status,
        int attemptCount,
        ZonedDateTime nextAttemptAt,
        String lastError,
        ZonedDateTime publishedAt,
        Integer publishedPartition,
        Long publishedOffset
    ) {
        static Snapshot of(OrderOutbox o) {
            return new Snapshot(o.getStatus(), o.getAttemptCount(), o.getNextAttemptAt(), o.getLastError(),
                o.getPublishedAt(), o.getPublishedPartition(), o.getPublishedOffset());
        }
    }

    private static void assertRejectedUnchanged(OrderOutbox outbox, Executable transition) {
        Snapshot before = Snapshot.of(outbox);
        assertThatThrownBy(transition::execute)
            .isInstanceOf(CoreException.class)
            .extracting("errorType").isEqualTo(ErrorType.CONFLICT);
        assertThat(Snapshot.of(outbox)).isEqualTo(before);
    }

    @DisplayName("보내려고 맡을 때(startSending), ")
    @Nested
    class StartSending {

        @DisplayName("차례가 된 PENDING은 SENDING이 되고, 기한은 지금부터 1분 뒤다. 시도 횟수는 그대로다.")
        @Test
        void pendingBecomesSendingWithLease() {
            // arrange
            OrderOutbox outbox = pending();

            // act
            outbox.startSending(T0);

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.SENDING);
            assertThat(outbox.getNextAttemptAt()).isEqualTo(T0.plusMinutes(1));
            assertThat(outbox.getAttemptCount()).isZero();
        }

        @DisplayName("아직 차례가 아닌 PENDING(재시도 대기 중)은 거절하고 값을 바꾸지 않는다.")
        @Test
        void rejectsPendingBeforeDue() {
            // arrange — 한 번 실패해 T0+1s+10s가 다음 차례
            OrderOutbox outbox = sending();
            outbox.recordFailure("broker down", T0.plusSeconds(1), DELAY);

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.startSending(T0.plusSeconds(10)));
        }

        @DisplayName("기한이 지난 SENDING(보내다 멈춘 행)은 다시 맡는다 — 기한과 같은 시각도 지난 것으로 본다.")
        @Test
        void retakesExpiredSending() {
            // arrange
            OrderOutbox outbox = sending();
            ZonedDateTime leaseEnd = T0.plusMinutes(1);

            // act
            outbox.startSending(leaseEnd);

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.SENDING);
            assertThat(outbox.getNextAttemptAt()).isEqualTo(leaseEnd.plusMinutes(1));
        }

        @DisplayName("기한이 남은 SENDING(누가 보내는 중)은 거절하고 값을 바꾸지 않는다.")
        @Test
        void rejectsSendingWithinLease() {
            // arrange
            OrderOutbox outbox = sending();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.startSending(T0.plusSeconds(59)));
        }

        @DisplayName("PUBLISHED는 다시 맡지 않는다.")
        @Test
        void rejectsPublished() {
            // arrange
            OrderOutbox outbox = published();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.startSending(T0.plusHours(1)));
        }

        @DisplayName("BLOCKED는 재개하기 전에는 맡지 않는다.")
        @Test
        void rejectsBlocked() {
            // arrange
            OrderOutbox outbox = blocked();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.startSending(T0.plusHours(1)));
        }
    }

    @DisplayName("broker 확인(ack)을 기록할 때(markPublished), ")
    @Nested
    class MarkPublished {

        @DisplayName("SENDING은 PUBLISHED가 되고, 기록 시각과 broker가 알려 준 partition·offset이 남는다.")
        @Test
        void sendingBecomesPublished() {
            // arrange
            OrderOutbox outbox = sending();

            // act
            outbox.markPublished(2, 7L, T0.plusSeconds(1));

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED);
            assertThat(outbox.getPublishedAt()).isEqualTo(T0.plusSeconds(1));
            assertThat(outbox.getPublishedPartition()).isEqualTo(2);
            assertThat(outbox.getPublishedOffset()).isEqualTo(7L);
        }

        @DisplayName("맡지 않은 PENDING에는 기록하지 않는다 (SENDING을 거치지 않은 send는 버그).")
        @Test
        void rejectsPending() {
            // arrange
            OrderOutbox outbox = pending();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.markPublished(2, 7L, T0.plusSeconds(1)));
        }

        @DisplayName("이미 PUBLISHED면 늦게 온 두 번째 기록을 거절하고, 처음 위치를 그대로 둔다.")
        @Test
        void rejectsSecondRecord() {
            // arrange
            OrderOutbox outbox = published();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.markPublished(0, 99L, T0.plusSeconds(5)));
        }

        @DisplayName("BLOCKED에는 기록하지 않는다.")
        @Test
        void rejectsBlocked() {
            // arrange
            OrderOutbox outbox = blocked();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.markPublished(2, 7L, T0.plusHours(1)));
        }
    }

    @DisplayName("send 실패를 기록할 때(recordFailure), ")
    @Nested
    class RecordFailure {

        @DisplayName("첫 실패: PENDING으로 돌아가고, 시도 1, 다음 차례는 지금 + 지연, 원인이 남는다.")
        @Test
        void firstFailureWaitsForRetry() {
            // arrange
            OrderOutbox outbox = sending();

            // act
            outbox.recordFailure("broker down", T0.plusSeconds(1), DELAY);

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
            assertThat(outbox.getNextAttemptAt()).isEqualTo(T0.plusSeconds(11));
            assertThat(outbox.getLastError()).isEqualTo("broker down");
        }

        @DisplayName("두 번째 실패까지는 PENDING이다 (시도 2).")
        @Test
        void secondFailureStillPending() {
            // arrange
            OrderOutbox outbox = sending();
            outbox.recordFailure("broker down 1", T0.plusSeconds(1), DELAY);
            outbox.startSending(T0.plusSeconds(11));

            // act
            outbox.recordFailure("broker down 2", T0.plusSeconds(12), DELAY);

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(outbox.getAttemptCount()).isEqualTo(2);
            assertThat(outbox.getNextAttemptAt()).isEqualTo(T0.plusSeconds(22));
        }

        @DisplayName("세 번째 실패면 BLOCKED가 된다 (시도 3, 마지막 원인).")
        @Test
        void thirdFailureBlocks() {
            // act
            OrderOutbox outbox = blocked();

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED);
            assertThat(outbox.getAttemptCount()).isEqualTo(3);
            assertThat(outbox.getLastError()).isEqualTo("broker down 3");
        }

        @DisplayName("원인이 1000자를 넘으면 앞 1000자만 남긴다 (컬럼 길이).")
        @Test
        void truncatesLongError() {
            // arrange
            OrderOutbox outbox = sending();
            String longError = "x".repeat(1000) + "TAIL";

            // act
            outbox.recordFailure(longError, T0.plusSeconds(1), DELAY);

            // assert
            assertThat(outbox.getLastError()).hasSize(1000).doesNotContain("TAIL");
        }

        @DisplayName("맡지 않은 PENDING에는 실패를 기록하지 않는다 — 시도 횟수가 늘지 않는다.")
        @Test
        void rejectsPending() {
            // arrange
            OrderOutbox outbox = pending();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.recordFailure("broker down", T0.plusSeconds(1), DELAY));
        }

        @DisplayName("PUBLISHED에 늦게 온 실패는 거절한다 — 성공을 덮지 않는다.")
        @Test
        void rejectsPublished() {
            // arrange
            OrderOutbox outbox = published();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.recordFailure("late timeout", T0.plusSeconds(40), DELAY));
        }
    }

    @DisplayName("사람이 재개할 때(resume), ")
    @Nested
    class Resume {

        @DisplayName("BLOCKED는 PENDING이 되고, 시도 0, 바로 차례다. 마지막 원인은 지우지 않는다.")
        @Test
        void blockedBecomesPending() {
            // arrange
            OrderOutbox outbox = blocked();
            ZonedDateTime resumedAt = T0.plusHours(1);

            // act
            outbox.resume(resumedAt);

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(outbox.getAttemptCount()).isZero();
            assertThat(outbox.getNextAttemptAt()).isEqualTo(resumedAt);
            assertThat(outbox.getLastError()).isEqualTo("broker down 3");
        }

        @DisplayName("재개한 행은 다시 3번의 기회를 갖는다 — 한 번 실패해도 BLOCKED가 아니다.")
        @Test
        void resumedRowGetsThreeAttemptsAgain() {
            // arrange
            OrderOutbox outbox = blocked();
            ZonedDateTime resumedAt = T0.plusHours(1);
            outbox.resume(resumedAt);
            outbox.startSending(resumedAt);

            // act
            outbox.recordFailure("broker down again", resumedAt.plusSeconds(1), DELAY);

            // assert
            assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING);
            assertThat(outbox.getAttemptCount()).isEqualTo(1);
        }

        @DisplayName("PUBLISHED는 재개할 수 없다 (사람이 eventId를 잘못 넣은 경우).")
        @Test
        void rejectsPublished() {
            // arrange
            OrderOutbox outbox = published();

            // act & assert
            assertRejectedUnchanged(outbox, () -> outbox.resume(T0.plusHours(1)));
        }

        @DisplayName("PENDING·SENDING은 재개할 대상이 아니다.")
        @Test
        void rejectsPendingAndSending() {
            // arrange
            OrderOutbox pendingOutbox = pending();
            OrderOutbox sendingOutbox = sending();

            // act & assert
            assertRejectedUnchanged(pendingOutbox, () -> pendingOutbox.resume(T0.plusHours(1)));
            assertRejectedUnchanged(sendingOutbox, () -> sendingOutbox.resume(T0.plusHours(1)));
        }
    }
}
