package com.loopers.application.order;

import com.loopers.application.user.IdentifyUser;
import com.loopers.domain.order.OrderRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetOrderFacade {
    private final IdentifyUser users;
    private final OrderRepository orders;
    public OrderInfo get(Long userId, long orderId) {
        long owner = users.require(userId);
        return OrderInfo.from(orders.findById(orderId).filter(order -> order.getUserId() == owner)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND)));
    }
    public List<OrderInfo> list(Long userId) {
        return orders.findByUserId(users.require(userId)).stream().map(OrderInfo::from).toList();
    }
}
