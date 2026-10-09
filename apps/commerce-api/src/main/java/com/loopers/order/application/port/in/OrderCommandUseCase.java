package com.loopers.order.application.port.in;

import com.loopers.order.domain.OrderLines;

import java.util.List;

/**
 * 고객 주문을 바꾸는 유스케이스의 입구(입력 포트): 생성(DRAFT), 확정(포인트 결제). 조회는 OrderQueryService가 맡는다.
 */
public interface OrderCommandUseCase {

    OrderInfo create(Long userId, List<OrderLines.Line> requestedLines);

    OrderInfo confirm(Long userId, Long orderId);
}
