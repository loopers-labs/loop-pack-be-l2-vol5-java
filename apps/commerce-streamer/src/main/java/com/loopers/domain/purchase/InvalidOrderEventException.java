package com.loopers.domain.purchase;

/** 이벤트 계약을 지키지 않은 메시지. 구매 활동을 만들지 않고 오류로 드러낸다(조용히 건너뛰지 않는다). */
public class InvalidOrderEventException extends RuntimeException {

    public InvalidOrderEventException(String message) {
        super(message);
    }
}
