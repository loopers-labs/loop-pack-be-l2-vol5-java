package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 주문 목록에 결제 시도를 붙인다 — 고객·관리자 조회가 같이 쓴다. 결제 시도는 주문 id로 한 번에 읽는다(주문마다 따로 읽지 않는다).
 * 결제 시도가 없는 주문(DRAFT, 이전 POINT 결제)은 payment가 비어 있다(docs/week5/design.md 6번).
 */
@RequiredArgsConstructor
@Component
public class OrderInfoAssembler {

    private final PaymentAttemptRepository paymentAttemptRepository;

    public List<OrderInfo> withPayments(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        Map<Long, PaymentAttempt> attemptsByOrderId = paymentAttemptRepository.findAllByOrderIdIn(orderIds).stream()
            .collect(Collectors.toMap(PaymentAttempt::getOrderId, Function.identity()));
        return orders.stream()
            .map(order -> OrderInfo.from(order, attemptsByOrderId.get(order.getId())))
            .toList();
    }
}
