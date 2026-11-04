package com.loopers.domain.order;

import com.loopers.domain.order.OrderConfirmedOutboxModel.Transition;
import com.loopers.support.error.CoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * Outbox 모델의 전달 상태 행동과 저장 정합성 검사(설계 W7.3-3, O9 모델 부분). 저장된 행을 흉내 내려고 PK 와 손상 상태만 reflection 으로 둔다.
 * 실제 잠금·commit 은 OrderOutboxRelayIntegrationTest 가 본다.
 */
@DisplayName("Outbox 모델은 허용된 상태·시도 번호에서만 전이하고, 불변 사실과 누적 횟수를 지키며, 손상 상태를 정상으로 받지 않는다.")
class OrderConfirmedOutboxStateTest {

    private static final ZonedDateTime T0 = ZonedDateTime.of(2026, 11, 4, 1, 0, 0, 0, ZoneOffset.UTC);
    private static final String PAYLOAD = "{\"stored\":\"raw\"}";

    private static OrderConfirmedOutboxModel stored() {
        OrderConfirmedOutboxModel outbox = OrderConfirmedOutboxModel.pending(
            new OrderOutboxMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", PAYLOAD), T0);
        ReflectionTestUtils.setField(outbox, "id", 7L);
        return outbox;
    }

    private static OrderConfirmedOutboxModel started() {
        OrderConfirmedOutboxModel outbox = stored();
        outbox.beginAttempt(T0, T0.plusSeconds(5));
        return outbox;
    }

    @DisplayName("시도 시작은 두 횟수를 1씩 더하고 provisional 다음 시각만 바꾸며, 저장 원문은 raw 그대로 복사된다.")
    @Test
    void beginAttemptCountsAndKeepsFacts() {
        OrderConfirmedOutboxModel outbox = started();
        assertAll(
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(1L),
            () -> assertThat(outbox.getCycleAttemptCount()).isEqualTo(1),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(T0.plusSeconds(5)),
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.storedMessage())
                .isEqualTo(new OrderOutboxStoredMessage("order-confirmed:42", 42L, "OrderConfirmed", 1, "42", PAYLOAD))
        );
    }

    @DisplayName("미래 PENDING·provisional 이 과거·UTC 마이크로초가 아닌 시각·PUBLISHED 행의 시작은 거절한다.")
    @Test
    void beginAttemptRejectsInvalid() {
        OrderConfirmedOutboxModel published = started();
        published.markPublished(1L, T0.plusSeconds(1));
        assertAll(
            () -> assertThatThrownBy(() -> stored().beginAttempt(T0.minusSeconds(1), T0.plusSeconds(5))).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> stored().beginAttempt(T0, T0.minusSeconds(1))).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> stored().beginAttempt(T0.withZoneSameInstant(ZoneId.of("Asia/Seoul")), T0.plusSeconds(5)))
                .isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> stored().beginAttempt(T0, T0.plusNanos(1))).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> published.beginAttempt(T0.plusSeconds(10), T0.plusSeconds(15))).isInstanceOf(CoreException.class)
        );
    }

    @DisplayName("누적 횟수가 long 상한이면 더하기 전에 거절하고 값을 바꾸지 않는다.")
    @Test
    void attemptCountOverflowIsRejected() {
        OrderConfirmedOutboxModel outbox = stored();
        ReflectionTestUtils.setField(outbox, "attemptCount", Long.MAX_VALUE);
        assertThatThrownBy(() -> outbox.beginAttempt(T0, T0.plusSeconds(5))).isInstanceOf(CoreException.class);
        assertThat(outbox.getCycleAttemptCount()).isZero();
    }

    @DisplayName("발행 저장은 PUBLISHED·publishedAt·lastError=null 이고, 같은 번호의 반복은 ALREADY_PUBLISHED 로 바꾸지 않는다.")
    @Test
    void markPublished() {
        OrderConfirmedOutboxModel outbox = started();
        ReflectionTestUtils.setField(outbox, "lastError", "SEND_WAIT_TIMEOUT");
        assertAll(
            () -> assertThat(outbox.markPublished(1L, T0.plusSeconds(1))).isEqualTo(Transition.APPLIED),
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED),
            () -> assertThat(outbox.getPublishedAt()).isEqualTo(T0.plusSeconds(1)),
            () -> assertThat(outbox.getLastError()).isNull(),
            () -> assertThat(outbox.markPublished(1L, T0.plusSeconds(2))).isEqualTo(Transition.ALREADY_PUBLISHED),
            () -> assertThat(outbox.recordFailure(1L, "SEND_CALL_FAILED", T0.plusSeconds(9), 10)).isEqualTo(Transition.ALREADY_PUBLISHED),
            () -> assertThat(outbox.getPublishedAt()).isEqualTo(T0.plusSeconds(1)),
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(1L)
        );
    }

    @DisplayName("다른 시도 번호·시작 전(회차 0) 행의 결과 저장은 STALE_ATTEMPT 이고 아무것도 바꾸지 않는다.")
    @Test
    void staleAttemptChangesNothing() {
        OrderConfirmedOutboxModel outbox = started();
        OrderConfirmedOutboxModel fresh = stored();
        assertAll(
            () -> assertThat(outbox.markPublished(2L, T0.plusSeconds(1))).isEqualTo(Transition.STALE_ATTEMPT),
            () -> assertThat(outbox.recordFailure(0L, "SEND_CALL_FAILED", T0.plusSeconds(9), 10)).isEqualTo(Transition.STALE_ATTEMPT),
            () -> assertThat(fresh.blockInvalid(0L, "INVALID_OUTBOX_MESSAGE")).isEqualTo(Transition.STALE_ATTEMPT),
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(T0.plusSeconds(5)),
            () -> assertThat(fresh.getStatus()).isEqualTo(OrderOutboxStatus.PENDING)
        );
    }

    @DisplayName("한도 전 실패는 PENDING·관측 기준 다음 시각·원인 code, 한도에 닿은 실패는 BLOCKED·ATTEMPT_LIMIT_EXHAUSTED 와 원인이다.")
    @Test
    void recordFailureRespectsBudget() {
        OrderConfirmedOutboxModel retry = started();
        OrderConfirmedOutboxModel exhausted = started();
        assertAll(
            () -> assertThat(retry.recordFailure(1L, "SEND_COMPLETION_FAILED:x.Y", T0.plusSeconds(7), 10)).isEqualTo(Transition.APPLIED),
            () -> assertThat(retry.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(retry.getNextAttemptAt()).isEqualTo(T0.plusSeconds(7)),
            () -> assertThat(retry.getLastError()).isEqualTo("SEND_COMPLETION_FAILED:x.Y"),
            () -> assertThat(exhausted.recordFailure(1L, "SEND_WAIT_TIMEOUT", T0.plusSeconds(7), 1)).isEqualTo(Transition.APPLIED),
            () -> assertThat(exhausted.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED),
            () -> assertThat(exhausted.getLastError()).isEqualTo("ATTEMPT_LIMIT_EXHAUSTED:SEND_WAIT_TIMEOUT"),
            () -> assertThat(exhausted.getNextAttemptAt()).as("차단 행의 다음 시각은 바꾸지 않음").isEqualTo(T0.plusSeconds(5)),
            () -> assertThatThrownBy(() -> started().recordFailure(1L, " ", T0.plusSeconds(7), 10)).isInstanceOf(CoreException.class)
        );
    }

    @DisplayName("재개는 BLOCKED 만 PENDING·회차 0·now 로 바꾸고 누적 횟수·lastError·사실을 보존하며, 다른 상태는 그대로다.")
    @Test
    void resumeKeepsHistory() {
        OrderConfirmedOutboxModel outbox = started();
        outbox.blockInvalid(1L, "INVALID_OUTBOX_MESSAGE");
        OrderConfirmedOutboxModel pending = stored();
        OrderConfirmedOutboxModel published = started();
        published.markPublished(1L, T0.plusSeconds(1));
        assertAll(
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED),
            () -> assertThat(outbox.resume(T0.plusSeconds(30))).isEqualTo(Transition.APPLIED),
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.getCycleAttemptCount()).isZero(),
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(1L),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(T0.plusSeconds(30)),
            () -> assertThat(outbox.getLastError()).isEqualTo("INVALID_OUTBOX_MESSAGE"),
            () -> assertThat(outbox.getPayload()).isEqualTo(PAYLOAD),
            () -> assertThat(outbox.resume(T0.plusSeconds(31))).isEqualTo(Transition.ALREADY_PENDING),
            () -> assertThat(outbox.getNextAttemptAt()).isEqualTo(T0.plusSeconds(30)),
            () -> assertThat(pending.resume(T0)).isEqualTo(Transition.ALREADY_PENDING),
            () -> assertThat(published.resume(T0)).isEqualTo(Transition.ALREADY_PUBLISHED),
            () -> assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED)
        );
    }

    @DisplayName("PK 없음·횟수 음수/역전·상태별 시각 결합 위반·삭제 시각이 있는 행은 저장 정합성 오류다.")
    @Test
    void corruptStateIsRejected() {
        assertAll(
            () -> assertThatThrownBy(() -> corrupt("id", 0L).requireDeliveryState()).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> corrupt("attemptCount", -1L).requireDeliveryState()).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> corrupt("cycleAttemptCount", 1).requireDeliveryState()).as("회차>누적")
                .isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> corrupt("publishedAt", T0).requireDeliveryState()).as("PENDING 의 발행 시각")
                .isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> corrupt("deletedAt", T0).requireDeliveryState()).isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> corrupt("status", OrderOutboxStatus.PUBLISHED).requireDeliveryState())
                .as("발행 시각·횟수 없는 PUBLISHED").isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> corrupt("status", OrderOutboxStatus.BLOCKED).requireDeliveryState())
                .as("시도 없는 BLOCKED").isInstanceOf(CoreException.class),
            () -> assertThatThrownBy(() -> corrupt("nextAttemptAt", null).resume(T0)).isInstanceOf(CoreException.class)
        );
    }

    @DisplayName("같은 시도 번호인데 BLOCKED 로 바뀐 행에 발행 저장을 하면 예상 밖 전이로 거절한다.")
    @Test
    void unexpectedTransitionIsRejected() {
        OrderConfirmedOutboxModel outbox = started();
        outbox.blockInvalid(1L, "INVALID_OUTBOX_MESSAGE");
        assertThatThrownBy(() -> outbox.markPublished(1L, T0.plusSeconds(1))).isInstanceOf(CoreException.class);
        assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED);
    }

    private static OrderConfirmedOutboxModel corrupt(String field, Object value) {
        OrderConfirmedOutboxModel outbox = stored();
        ReflectionTestUtils.setField(outbox, field, value);
        return outbox;
    }
}
