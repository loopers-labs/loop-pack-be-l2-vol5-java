package com.loopers.application.payment;

import lombok.Getter;

/**
 * OrderConfirmed 발행이 성공으로 끝나지 않았을 때의 예외 (6주차 설계 D7).
 * FAILED 는 producer 가 포기한 "실패 확정", UNKNOWN 은 대기 상한 안에 결과를 못 받은 "미확인"이다.
 * 미확인 record 는 producer 버퍼에 남아 delivery.timeout.ms 동안 뒤에서 재시도되므로 실제로는 발행될 수 있다.
 * 호출자(Facade)는 outcome 을 로그에 남기고 주문 상태는 그대로 둔다.
 */
@Getter
public class OrderConfirmedPublishException extends RuntimeException {

    public enum Outcome {
        FAILED, UNKNOWN
    }

    private final Outcome outcome;
    private final String eventId;
    private final Long orderId;

    public OrderConfirmedPublishException(Outcome outcome, String eventId, Long orderId, String detail, Throwable cause) {
        super(String.format("OrderConfirmed 발행 %s. outcome=%s, eventId=%s, orderId=%d: %s",
            outcome == Outcome.FAILED ? "실패 확정" : "결과 미확인", outcome, eventId, orderId, detail), cause);
        this.outcome = outcome;
        this.eventId = eventId;
        this.orderId = orderId;
    }
}
