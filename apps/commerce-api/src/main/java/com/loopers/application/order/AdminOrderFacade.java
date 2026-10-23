package com.loopers.application.order;

import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttemptRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class AdminOrderFacade {

    private final OrderService orderService;
    private final PaymentAttemptRepository paymentAttemptRepository;

    @Transactional(readOnly = true)
    public List<OrderInfo> getAll() {
        List<OrderModel> orders = orderService.getAll();
        return OrderInfo.from(orders, paymentAttemptRepository.findByOrderIds(orders.stream().map(OrderModel::getId).toList()));
    }

    @Transactional(readOnly = true)
    public OrderInfo get(Long orderId) {
        return OrderInfo.from(orderService.get(orderId), paymentAttemptRepository.findByOrderId(orderId).orElse(null));
    }
}
