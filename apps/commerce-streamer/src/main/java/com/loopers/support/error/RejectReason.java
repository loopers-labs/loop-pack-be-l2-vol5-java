package com.loopers.support.error;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 다시 받아도 똑같이 실패하는 사유 (6주차 설계 4.2 · 4.3) */
@Getter
@RequiredArgsConstructor
public enum RejectReason {
    INVALID_FORMAT("메시지 형식이 계약과 다릅니다."),
    UNSUPPORTED_EVENT("지원하지 않는 이벤트 종류나 버전입니다."),
    KEY_MISMATCH("Kafka key 와 payload 의 주문이 다릅니다."),
    EMPTY_ITEMS("품목이 비어 있습니다."),
    INVALID_QUANTITY("수량은 1 이상이어야 합니다."),
    DUPLICATE_PRODUCT("같은 상품이 두 번 이상 있습니다."),
    TOO_PRECISE_TIME("확정 시각이 마이크로초보다 정밀합니다."),
    CONFLICTING_CONTENT("같은 eventId 에 다른 확정 시각이나 품목이 왔습니다."),
    CONFLICTING_EVENT_ID("이미 처리한 주문이 다른 eventId 로 왔습니다."),
    CONFLICTING_ORDER("이미 처리한 eventId 가 다른 주문으로 왔습니다.");

    private final String message;
}
