package com.loopers.domain.payment;

/**
 * 관찰을 적용한 결과, 주문·재고에 일으켜야 하는 효과. 시도가 <b>이번 호출에서</b> 종결로 바뀐 경우에만 SUCCEEDED·FAILED다.
 * 같은 결과가 다시 와도 이미 종결이라 NONE이므로, 호출자가 효과 수를 따로 세지 않아도 확정·반환이 한 번만 일어난다.
 */
public enum PaymentEffect {
    NONE,
    /** 주문을 CONFIRMED로, 재고 예약을 소비로 마무리한다. 재고는 다시 줄이지 않는다. */
    SUCCEEDED,
    /** 주문을 PAYMENT_FAILED로, 재고 예약을 한 번 반환한다. */
    FAILED
}
