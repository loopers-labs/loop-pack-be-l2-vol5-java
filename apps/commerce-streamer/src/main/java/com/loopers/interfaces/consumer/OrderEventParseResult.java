package com.loopers.interfaces.consumer;

import com.loopers.application.purchase.OrderConfirmedEvent;

public sealed interface OrderEventParseResult {

    record Parsed(OrderConfirmedEvent event) implements OrderEventParseResult {
    }

    // record 내용 자체가 틀려 받는 쪽을 고쳐도 같은 record로는 성공할 수 없는 경우(D70).
    record Invalid(Reason reason, String detail, String eventId, Long orderId) implements OrderEventParseResult {
    }

    // 받는 쪽을 고쳐 재시작하면 같은 record로 성공할 수 있는 경우(D70). 건너뛰지 않고 멈춘다.
    record Unsupported(String detail) implements OrderEventParseResult {
    }

    enum Reason {
        MALFORMED_JSON, MISSING_FIELD, INVALID_FIELD, EMPTY_ITEMS, NON_POSITIVE_QUANTITY, DUPLICATE_PRODUCT, KEY_MISMATCH
    }
}
