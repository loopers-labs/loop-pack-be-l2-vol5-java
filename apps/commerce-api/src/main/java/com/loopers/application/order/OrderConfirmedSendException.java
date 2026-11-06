package com.loopers.application.order;

/**
 * broker가 주문 확정 이벤트를 받았다고 응답하지 않았다(docs/week7/decisions.md D-9). relay는 원인과 상관없이 같은
 * 백오프로 다시 시도하므로(D-3) 원인별 예외 종류를 나누지 않는다.
 *
 * <p>메시지에는 topic·key처럼 짧은 식별 정보만 담는다. payload 전체는 담지 않는다 — last_error와 로그로 흘러가기 때문이다.
 */
public class OrderConfirmedSendException extends RuntimeException {

    public OrderConfirmedSendException(String message, Throwable cause) {
        super(message, cause);
    }
}
