package com.loopers.domain.order;

import com.loopers.domain.common.Money;
import jakarta.persistence.Embeddable;

/**
 * 주문 확정 때의 결제 결과. 결제한 금액이다.
 *
 * <p>결제 수단의 상태(포인트 잔액 등)는 담지 않는다. 결제 수단이 바뀌어도(PG 결제) 주문이 따라 바뀌지 않게 하기
 * 위해서다. 결제를 마친(CONFIRMED) 시점에 생기는 값이며 그 전의 주문에는 없다.
 */
@Embeddable
public record PaymentResult(Money paidAmount) {}
