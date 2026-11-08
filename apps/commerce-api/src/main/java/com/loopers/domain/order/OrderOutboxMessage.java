package com.loopers.domain.order;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;

/**
 * 최초 확정 TX 에서 한 번 만든 주문 확정 사건의 전송 사실(설계 W7.2-5). 생성 뒤 변경하지 않는 불변 scalar 값이다.
 * payload 는 encoder 가 만든 v1 JSON 원문이며 이 타입은 JSON 형식을 해석하지 않는다. Entity 재조회·Jackson·Kafka 의존이 없다.
 * 생성 시 양수 주문 ID·그 ID 에서 유도한 eventId·정규 10진 key·고정 type/version·비어 있지 않은 payload 를 검사한다.
 */
public record OrderOutboxMessage(String eventId, long orderId, String eventType, int schemaVersion, String messageKey,
                                 String payload) {

    public static final String EVENT_TYPE = "OrderConfirmed";
    public static final int SCHEMA_VERSION = 1;

    private static final String EVENT_ID_PREFIX = "order-confirmed:";

    public OrderOutboxMessage {
        if (orderId < 1L) {
            throw integrityError("Outbox 메시지의 주문 ID 는 양수여야 합니다.");
        }
        String canonicalKey = Long.toString(orderId);
        if (!(EVENT_ID_PREFIX + canonicalKey).equals(eventId)) {
            throw integrityError("Outbox 메시지의 eventId 가 주문 ID 에서 유도한 값이 아닙니다.");
        }
        if (!EVENT_TYPE.equals(eventType) || schemaVersion != SCHEMA_VERSION) {
            throw integrityError("Outbox 메시지의 사건 타입·버전이 OrderConfirmed v1 이 아닙니다.");
        }
        if (!canonicalKey.equals(messageKey)) {
            throw integrityError("Outbox 메시지의 key 가 주문 ID 의 정규 10진 문자열이 아닙니다.");
        }
        if (payload == null || payload.isBlank()) {
            throw integrityError("Outbox 메시지의 payload 가 비어 있습니다.");
        }
    }

    // 오류 문구에는 입력값·payload 원문을 넣지 않는다.
    private static CoreException integrityError(String message) {
        return new CoreException(ErrorType.INTERNAL_ERROR, message);
    }
}
