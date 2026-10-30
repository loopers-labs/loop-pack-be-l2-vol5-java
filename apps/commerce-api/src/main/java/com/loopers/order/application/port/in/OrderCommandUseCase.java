package com.loopers.order.application.port.in;

import com.loopers.order.domain.OrderLines;

import java.util.List;

/**
 * 고객 주문을 바꾸는 유스케이스의 입구(입력 포트): 생성(DRAFT). 조회는 OrderQueryService가 맡는다.
 * 확정은 W5부터 PG 결제 요청(payment의 PaymentRequestUseCase)이 맡는다 (W5 plan.md 4장).
 */
public interface OrderCommandUseCase {

    OrderInfo create(Long userId, List<OrderLines.Line> requestedLines);
}
