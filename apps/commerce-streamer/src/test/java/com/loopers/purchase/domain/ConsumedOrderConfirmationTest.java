package com.loopers.purchase.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W6 D-6 판정 — 완료 기록(eventId X · 주문 42 · 7×2)이 이미 있을 때 들어온 이벤트를 중복과 충돌로 나눈다.
 */
class ConsumedOrderConfirmationTest {

    private static final String AT = "2026-10-28T14:03:12.123456+09:00";

    private static OrderConfirmedMessage message(String eventId, Long orderId, int quantity) {
        return new OrderConfirmedMessage(eventId, "OrderConfirmed", 1, orderId, AT, List.of(new OrderConfirmedMessage.Item(7L, quantity)));
    }

    private final ConsumedOrderConfirmation existing = ConsumedOrderConfirmation.of(message("evt-X", 42L, 2), "{원문}");

    @DisplayName("같은 eventId · 같은 사실이면 중복(DUPLICATE) — no-op 대상이다.")
    @Test
    void sameEventSameFactIsDuplicate() {
        assertThat(existing.judge(message("evt-X", 42L, 2))).isEqualTo(ConsumptionVerdict.DUPLICATE);
    }

    @DisplayName("같은 eventId인데 수량이 다르면(7×3) 충돌이다 — 같은 ID, 다른 사실.")
    @Test
    void sameEventDifferentFactIsConflict() {
        assertThat(existing.judge(message("evt-X", 42L, 3))).isEqualTo(ConsumptionVerdict.CONFLICT_SAME_EVENT_DIFFERENT_FACT);
    }

    @DisplayName("같은 eventId인데 주문이 다르면(43) 충돌이다 — 같은 ID, 다른 사실.")
    @Test
    void sameEventDifferentOrderIsConflict() {
        assertThat(existing.judge(message("evt-X", 43L, 2))).isEqualTo(ConsumptionVerdict.CONFLICT_SAME_EVENT_DIFFERENT_FACT);
    }

    @DisplayName("같은 주문인데 eventId가 다르면(evt-Y) 사실이 같아도 충돌이다 — 한 주문의 확정 사실이 둘.")
    @Test
    void sameOrderDifferentEventIsConflict() {
        assertThat(existing.judge(message("evt-Y", 42L, 2))).isEqualTo(ConsumptionVerdict.CONFLICT_SAME_ORDER_DIFFERENT_EVENT);
    }
}
