package com.loopers.domain.payment;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

class PaymentCallbackInboxTest {

    private static final ZonedDateTime NOW = ZonedDateTime.parse("2026-10-22T10:00:00+09:00");

    private static PaymentCallbackInbox received() {
        return new PaymentCallbackInbox("{\"status\":\"SUCCESS\"}", "TEST-TX-1", "LP-ORD-0000000001", NOW);
    }

    @DisplayName("받은 callback은 원문과 키를 그대로 담고, 받은 즉시 처리할 차례가 된다.")
    @Test
    void storesRawPayload_andIsDueImmediately() {
        PaymentCallbackInbox inbox = received();
        assertAll(
            () -> assertThat(inbox.getStatus()).isEqualTo(CallbackInboxStatus.RECEIVED),
            () -> assertThat(inbox.getRawPayload()).isEqualTo("{\"status\":\"SUCCESS\"}"),
            () -> assertThat(inbox.getNextTryAt()).isEqualTo(NOW),
            () -> assertThat(inbox.getTryCount()).isZero()
        );
    }

    @DisplayName("조회에 실패하면 다음 처리 시각을 미루고 재시도 상태가 되며, 세 번째 실패에서 포기한다. (D-021, D-042)")
    @Test
    void retriesTwice_thenGivesUpOnThirdFailure() {
        // arrange
        PaymentCallbackInbox inbox = received();
        ZonedDateTime next = NOW.plusMinutes(3);

        // act & assert
        inbox.failed(7L, "PG 조회 실패", NOW, next);
        assertAll(
            () -> assertThat(inbox.getStatus()).isEqualTo(CallbackInboxStatus.RETRY),
            () -> assertThat(inbox.getTryCount()).isEqualTo(1),
            () -> assertThat(inbox.getNextTryAt()).isEqualTo(next),
            () -> assertThat(inbox.getAttemptId()).isEqualTo(7L),
            () -> assertThat(inbox.getProcessedAt()).isNull()
        );

        inbox.failed(7L, "PG 조회 실패", next, next.plusMinutes(3));
        assertThat(inbox.getStatus()).isEqualTo(CallbackInboxStatus.RETRY);

        inbox.failed(7L, "PG 조회 실패", next.plusMinutes(3), next.plusMinutes(6));
        assertAll(
            () -> assertThat(inbox.getStatus()).isEqualTo(CallbackInboxStatus.GAVE_UP),
            () -> assertThat(inbox.getTryCount()).isEqualTo(PaymentCallbackInbox.MAX_TRIES),
            () -> assertThat(inbox.getStatus().isPending()).isFalse(),
            () -> assertThat(inbox.getProcessedAt()).isEqualTo(next.plusMinutes(3))
        );
    }

    @DisplayName("반영을 마치면 완료, 맞는 결제 시도가 없으면 매칭 실패로 끝나고 더는 처리할 차례가 아니다.")
    @Test
    void endsAsDoneOrUnmatched() {
        PaymentCallbackInbox done = received();
        PaymentCallbackInbox unmatched = received();

        done.done(7L, NOW);
        unmatched.unmatched(NOW);

        assertAll(
            () -> assertThat(done.getStatus()).isEqualTo(CallbackInboxStatus.DONE),
            () -> assertThat(done.getStatus().isPending()).isFalse(),
            () -> assertThat(unmatched.getStatus()).isEqualTo(CallbackInboxStatus.UNMATCHED),
            () -> assertThat(unmatched.getStatus().isPending()).isFalse()
        );
    }
}
