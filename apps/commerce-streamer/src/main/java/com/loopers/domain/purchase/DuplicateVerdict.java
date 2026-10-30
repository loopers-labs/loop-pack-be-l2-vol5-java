package com.loopers.domain.purchase;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * unique에 걸린 확정을, 롤백 뒤 새 트랜잭션에서 다시 읽은 기록과 비교한 판정(docs/week6/decisions.md D-10, D-18).
 *
 * <table>
 *   <tr><th>저장된 기록</th><th>판정</th></tr>
 *   <tr><td>같은 eventId·같은 orderId·같은 사실</td><td>{@link SameFact} — no-op, ack</td></tr>
 *   <tr><td>같은 eventId인데 orderId나 사실이 다름</td><td>conflict</td></tr>
 *   <tr><td>같은 orderId인데 eventId가 다름(내용이 같아도)</td><td>conflict</td></tr>
 *   <tr><td>처리 기록 없이 구매 활동만 있음</td><td>conflict — 처음 사실은 남아 있는 활동으로 다시 만든다</td></tr>
 *   <tr><td>아무것도 없음</td><td>{@link Unexplained} — 분류하지 못한 실패라 멈춤으로 보낸다(D-29)</td></tr>
 * </table>
 */
public sealed interface DuplicateVerdict {

    record SameFact() implements DuplicateVerdict {}

    record Conflict(PurchaseActivityConflict.Reason reason, String firstEventId, PurchaseFact firstFact) implements DuplicateVerdict {}

    record Unexplained() implements DuplicateVerdict {}

    static DuplicateVerdict judge(OrderConfirmation incoming, PurchaseFact incomingFact,
                                  Optional<ProcessedOrderConfirmation> byEventId,
                                  Optional<ProcessedOrderConfirmation> byOrderId,
                                  List<ProductPurchaseActivity> activitiesOfOrder) {
        if (byEventId.isPresent()) {
            ProcessedOrderConfirmation stored = byEventId.get();
            if (stored.getOrderId().equals(incoming.orderId()) && stored.getFact().equals(incomingFact)) {
                return new SameFact();
            }
            return new Conflict(PurchaseActivityConflict.Reason.SAME_EVENT_DIFFERENT_FACT, stored.getEventId(), stored.getFact());
        }
        if (byOrderId.isPresent()) {
            ProcessedOrderConfirmation stored = byOrderId.get();
            return new Conflict(PurchaseActivityConflict.Reason.SAME_ORDER_DIFFERENT_EVENT, stored.getEventId(), stored.getFact());
        }
        if (!activitiesOfOrder.isEmpty()) {
            return new Conflict(PurchaseActivityConflict.Reason.ACTIVITY_WITHOUT_PROCESSED_RECORD, null,
                factOf(incoming.orderId(), activitiesOfOrder));
        }
        return new Unexplained();
    }

    private static PurchaseFact factOf(Long orderId, List<ProductPurchaseActivity> activities) {
        // 활동 행마다 시각이 같아야 하지만, 불일치를 드러내는 기록이므로 첫 행의 시각을 쓴다.
        ZonedDateTime confirmedAt = activities.get(0).getConfirmedAt();
        return PurchaseFact.of(orderId, confirmedAt, activities.stream()
            .map(activity -> new OrderConfirmation.Item(activity.getProductId(), activity.getQuantity()))
            .toList());
    }
}
