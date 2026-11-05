package com.loopers.application.outbox;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.outbox.OutboxEvent;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 빠진 이벤트 복구의 판단(docs/week7/decisions.md D-5, D-14). DB 없이 사실만 받아 결과를 정한다 — 가벼운 테스트로 check에서
 * 매번 확인하려고 저장·조회와 떼어 둔다. 판단은 네 갈래다.
 *
 * <ol>
 *   <li>사실이 모자람(CONFIRMED가 아님·confirmed_at 없음·품목 없음) → 오류. 추측해서 채우지 않는다({@link #requireStoredFacts}).</li>
 *   <li>행 없음 → 새 행({@link Decision#CREATE}).</li>
 *   <li>같은 행 있음(eventId·orderId·eventType·schemaVersion·messageKey·payload가 글자까지 같음) → 아무것도 안 함.</li>
 *   <li>다른 행 있음 → 오류. 다른 payload가 같은 eventId로 나가면 consumer가 conflict로 기록한다(W6 D-18, INV-706).</li>
 * </ol>
 * 행의 상태(PENDING·PUBLISHED·BLOCKED)와 시도 기록은 비교하지 않는다 — 복구는 "보낼 사실"이 같은지만 본다.
 */
public final class OutboxBackfillJudge {

    /** 사실이 충분할 때의 판단. 오류 갈래는 예외로 드러낸다. */
    public enum Decision {
        /** 행이 없다. 확정 TX와 같은 팩토리로 만든 후보를 저장한다. */
        CREATE,
        /** 같은 행이 이미 있다. 쓰지 않는다. */
        ALREADY_PRESENT_SAME
    }

    /** 복구가 읽는 저장된 주문 사실. 현재 상품 정보와 now()는 들어오지 않는다(D-5). */
    public record OrderFacts(Long orderId, OrderStatus status, ZonedDateTime confirmedAt, int itemCount) {

        public static OrderFacts of(Order order) {
            return new OrderFacts(order.getId(), order.getStatus(), order.getConfirmedAt(), order.getItems().size());
        }
    }

    /** Outbox 행에서 "보낼 사실"만. 비교 대상 여섯 칸이다. */
    public record RowFacts(String eventId, Long orderId, String eventType, int schemaVersion, String messageKey, String payload) {

        public static RowFacts of(OutboxEvent event) {
            return new RowFacts(event.getEventId(), event.getOrderId(), event.getEventType(), event.getSchemaVersion(),
                event.getMessageKey(), event.getPayload());
        }
    }

    private OutboxBackfillJudge() {}

    /**
     * 저장된 사실만으로 이벤트를 만들 수 있는지 본다. 모자라면 무엇이 모자란지 적어 409로 거절한다 — 요청자가 입력을 바꿔서가
     * 아니라 주문의 상태가 먼저 바뀌어야 풀리기 때문이다.
     */
    public static void requireStoredFacts(OrderFacts facts) {
        List<String> missing = new ArrayList<>();
        if (facts.status() != OrderStatus.CONFIRMED) {
            missing.add("CONFIRMED가 아님(현재: " + facts.status() + ")");
        }
        if (facts.confirmedAt() == null) {
            missing.add("confirmed_at 없음");
        }
        if (facts.itemCount() <= 0) {
            missing.add("저장된 품목 없음");
        }
        if (!missing.isEmpty()) {
            throw new CoreException(ErrorType.CONFLICT,
                "[orderId = " + facts.orderId() + "] 저장된 사실이 모자라 확정 이벤트를 복구할 수 없습니다: " + String.join(", ", missing));
        }
    }

    /**
     * 후보 행과 이미 있는 행을 비교한다. 다르면 어느 칸이 다른지 적어 409로 거절한다. payload는 내용을 싣지 않고 길이만 적는다 —
     * 예외 메시지는 로그로 흘러간다.
     *
     * @param candidate 확정 TX와 같은 팩토리(OrderConfirmedOutbox)로 방금 만든 후보
     * @param existing  같은 eventId로 찾은 기존 행. 없으면 빈 값
     */
    public static Decision decide(RowFacts candidate, Optional<RowFacts> existing) {
        if (existing.isEmpty()) {
            return Decision.CREATE;
        }
        List<String> differences = differences(candidate, existing.get());
        if (!differences.isEmpty()) {
            throw new CoreException(ErrorType.CONFLICT, "[eventId = " + candidate.eventId()
                + "] 이미 있는 Outbox 행이 저장된 사실로 만든 행과 다릅니다: " + String.join(", ", differences));
        }
        return Decision.ALREADY_PRESENT_SAME;
    }

    private static List<String> differences(RowFacts candidate, RowFacts existing) {
        List<String> differences = new ArrayList<>();
        compare(differences, "eventId", candidate.eventId(), existing.eventId());
        compare(differences, "orderId", candidate.orderId(), existing.orderId());
        compare(differences, "eventType", candidate.eventType(), existing.eventType());
        compare(differences, "schemaVersion", candidate.schemaVersion(), existing.schemaVersion());
        compare(differences, "messageKey", candidate.messageKey(), existing.messageKey());
        if (!Objects.equals(candidate.payload(), existing.payload())) {
            differences.add("payload(길이 기존 " + length(existing.payload()) + " / 복구 " + length(candidate.payload()) + ")");
        }
        return differences;
    }

    private static void compare(List<String> differences, String field, Object candidate, Object existing) {
        if (!Objects.equals(candidate, existing)) {
            differences.add(field + "(기존 " + existing + " / 복구 " + candidate + ")");
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.length();
    }
}
