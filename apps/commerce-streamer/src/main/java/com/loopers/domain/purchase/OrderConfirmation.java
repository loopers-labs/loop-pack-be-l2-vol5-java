package com.loopers.domain.purchase;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 계약 검사를 통과한 주문 확정 사실(docs/week6/design.md 2번 이벤트 계약). 만들어졌다면 이벤트 자체의 약속은 지켜진 것이다 —
 * insert 전에 걸러 두어야 Duplicate가 "다른 이벤트와 겹쳤다"는 한 가지 뜻만 갖는다(docs/week6/decisions.md D-19).
 *
 * <p>검사하는 약속(D-19):
 * <ul>
 *   <li>형식: eventType이 OrderConfirmed, schemaVersion이 1, 필수 필드가 있다. confirmedAt은 ISO-8601 시각이고 마이크로초보다
 *       잘게 쪼개지지 않는다(D-6 — 그보다 잘게 오면 정규 형식으로 옮길 때 사실이 잘린다).</li>
 *   <li>식별 관계: record key가 orderId의 문자열과 같다. eventId의 모양은 여기서 보지 않는다 — 같은 주문의 다른 eventId는
 *       이미 처리한 기록과 비교해야 드러나는 conflict다(D-18). 여기서 막으면 그 판정까지 가지 못한다.</li>
 *   <li>items: 비어 있지 않고, productId가 중복되지 않으며, 수량이 1 이상이다. producer도 같은 약속을 지키지만 믿고 생략하지 않는다.</li>
 * </ul>
 * items는 productId 오름차순으로 보관한다. confirmedAt은 UTC로 보관한다.
 */
public record OrderConfirmation(String eventId, Long orderId, ZonedDateTime confirmedAt, List<Item> items) {

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    public OrderConfirmation {
        items = List.copyOf(items);
    }

    /**
     * 읽어 낸 값으로 계약을 검사해 만든다.
     *
     * @throws OrderConfirmationViolation 약속을 하나라도 어기면. 사유는 처음 걸린 약속이다
     */
    public static OrderConfirmation from(String recordKey, Draft draft) {
        if (!EVENT_TYPE.equals(draft.eventType())) {
            throw new OrderConfirmationViolation("eventType이 " + EVENT_TYPE + "가 아님: " + draft.eventType());
        }
        if (!Objects.equals(draft.schemaVersion(), SCHEMA_VERSION)) {
            throw new OrderConfirmationViolation("schemaVersion이 " + SCHEMA_VERSION + "이 아님: " + draft.schemaVersion());
        }
        require(draft.eventId(), "eventId");
        require(draft.orderId(), "orderId");
        require(draft.confirmedAt(), "confirmedAt");
        require(draft.items(), "items");
        if (!String.valueOf(draft.orderId()).equals(recordKey)) {
            throw new OrderConfirmationViolation("record key가 orderId와 다름: key=" + recordKey + ", orderId=" + draft.orderId());
        }
        return new OrderConfirmation(draft.eventId(), draft.orderId(), parseConfirmedAt(draft.confirmedAt()), items(draft.items()));
    }

    private static void require(Object value, String field) {
        if (value == null) {
            throw new OrderConfirmationViolation("필수 필드 없음: " + field);
        }
    }

    private static ZonedDateTime parseConfirmedAt(String text) {
        Instant instant;
        try {
            instant = Instant.parse(text);
        } catch (DateTimeParseException e) {
            throw new OrderConfirmationViolation("confirmedAt을 시각으로 읽을 수 없음: " + text, e);
        }
        if (instant.getNano() % 1_000 != 0) {
            throw new OrderConfirmationViolation("confirmedAt이 마이크로초보다 잘게 쪼개짐: " + text);
        }
        return instant.atZone(ZoneOffset.UTC);
    }

    private static List<Item> items(List<Draft.Item> drafts) {
        if (drafts.isEmpty()) {
            throw new OrderConfirmationViolation("items가 비어 있음");
        }
        Set<Long> productIds = new HashSet<>();
        for (Draft.Item draft : drafts) {
            if (draft == null || draft.productId() == null || draft.quantity() == null) {
                throw new OrderConfirmationViolation("품목의 필수 필드 없음: " + draft);
            }
            if (draft.quantity() < 1) {
                throw new OrderConfirmationViolation("수량이 1보다 작음: productId=" + draft.productId() + ", quantity=" + draft.quantity());
            }
            if (!productIds.add(draft.productId())) {
                throw new OrderConfirmationViolation("productId 중복: " + draft.productId());
            }
        }
        return drafts.stream()
            .map(draft -> new Item(draft.productId(), draft.quantity()))
            .sorted(Comparator.comparing(Item::productId))
            .toList();
    }

    public record Item(Long productId, int quantity) {}

    /**
     * 메시지에서 읽어 낸 그대로의 값. 아무 약속도 검사하지 않았으므로 모든 칸이 비어 있을 수 있다.
     * JSON을 읽는 일(형식 해석)은 application이 하고, 약속 검사는 여기서 한다.
     */
    public record Draft(String eventType, Integer schemaVersion, String eventId, Long orderId, String confirmedAt,
                        List<Item> items) {

        public record Item(Long productId, Integer quantity) {}
    }
}
