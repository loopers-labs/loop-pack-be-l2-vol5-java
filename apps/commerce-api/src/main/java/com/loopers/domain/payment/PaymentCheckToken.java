package com.loopers.domain.payment;

/**
 * 결과 반영 TX 에 넘기는 확인 토큰(설계 4.3). 순번은 없다. terminalAtStart 는 확인을 준비할 때 이미 종결이었는지이며
 * 종결 뒤 QUERY PENDING 이 늦은 관찰인지 후퇴 충돌인지 가르는 데만 쓴다.
 */
public record PaymentCheckToken(
    Long paymentId,
    Long orderId,
    ObservationSource source,
    boolean terminalAtStart
) {

    public PaymentCheckToken {
        if (paymentId == null || orderId == null || source == null) {
            throw new IllegalArgumentException("확인 토큰의 결제·주문·출처 필수");
        }
    }
}
