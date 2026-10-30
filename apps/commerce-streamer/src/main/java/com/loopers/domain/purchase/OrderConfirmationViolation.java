package com.loopers.domain.purchase;

/**
 * 받은 기록이 OrderConfirmed 계약을 어겼다(docs/week6/decisions.md D-8, D-19). 다시 해도 결과가 바뀌지 않는 실패라
 * 호출자는 주소와 원본을 계약 깨짐으로 기록하고 넘어간다. 원인은 producer 쪽에 있고, 알아챌 수 있는 곳은 consumer뿐이다.
 */
public class OrderConfirmationViolation extends RuntimeException {

    private final String reason;

    public OrderConfirmationViolation(String reason) {
        super(reason);
        this.reason = reason;
    }

    public OrderConfirmationViolation(String reason, Throwable cause) {
        super(reason, cause);
        this.reason = reason;
    }

    public String reason() {
        return reason;
    }
}
