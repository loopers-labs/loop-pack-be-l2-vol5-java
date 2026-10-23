package com.loopers.interfaces.api.payment;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.loopers.application.payment.PaymentOutcome;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttemptStatus;

public class PaymentV1Dto {

    /**
     * PG의 결과 알림 (docs/week5/pg-contract.md 3-5절). 알림은 확인 요청의 계기일 뿐이다.
     * {@code status}는 받지만 쓰지 않는다. 알림이 가리킨 거래를 PG에서 직접 조회한 결과만 주문에 적용한다.
     *
     * @param orderId PG가 부르는 주문 번호, 곧 우리의 providerOrderId다 ({@code LP-ORD-0000000042}).
     *                PG의 다른 메시지(거래 생성·조회 응답)와 같은 {@code orderId} 이름으로 받고, 과제 문장이 알림 본문을 설명하는 이름인
     *                {@code providerOrderId}로 와도 같은 값으로 받는다. 어느 쪽 이름으로 오든 PG 주문 번호 하나를 가리킨다
     */
    public record CallbackRequest(String transactionKey, @JsonAlias("providerOrderId") String orderId, String status) {
    }

    /** 알림을 보낸 PG가 볼 수 있는 최소한의 결과. 주문의 품목·금액은 담지 않는다. */
    public record CallbackResponse(Long orderId, OrderStatus orderStatus, PaymentAttemptStatus paymentStatus) {
        public static CallbackResponse from(PaymentOutcome outcome) {
            return new CallbackResponse(outcome.order().id(), outcome.order().status(), outcome.order().payment().status());
        }
    }
}
