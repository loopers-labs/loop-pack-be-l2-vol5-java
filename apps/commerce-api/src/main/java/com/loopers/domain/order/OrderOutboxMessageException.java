package com.loopers.domain.order;

/**
 * 저장 원문의 알려진 형식·버전·사실 오류(설계 W7.3-2·W7.3-3). 안전 code 만 가지며 payload·key 원문·parser 메시지·cause 를 담지 않는다.
 * 예상 밖 프로그램 오류는 이 타입으로 바꾸지 않는다.
 */
public class OrderOutboxMessageException extends RuntimeException {

    public enum Code {
        /** 필수 값·JSON 형식·타입·범위·메타데이터와 본문의 일치 위반. */
        INVALID_OUTBOX_MESSAGE,
        /** eventType·schemaVersion 이 OrderConfirmed v1 이 아님. */
        UNSUPPORTED_SCHEMA,
        /** 유효한 저장 원문이지만 기대한 사건과 identity·최초 순간·품목이 다름. */
        OUTBOX_FACT_MISMATCH
    }

    private final Code code;

    public OrderOutboxMessageException(Code code) {
        super(code.name());
        this.code = code;
    }

    public Code code() {
        return code;
    }
}
