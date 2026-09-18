package com.loopers.application.order;

import com.loopers.domain.order.OrderRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Component
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GetAdminOrderFacade {
    private final OrderRepository orders;
    public OrderInfo get(long id) {
        return OrderInfo.from(orders.findById(id).orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND)));
    }
    public Page<OrderInfo> list(Long userId, int page, int size) {
        if (page < 0 || size < 1 || size > 100 || (userId != null && userId <= 0)) { throw new CoreException(ErrorType.INVALID_REQUEST); }
        return orders.findAll(userId,PageRequest.of(page,size,Sort.by(Sort.Direction.DESC,"createdAt","id"))).map(OrderInfo::from);
    }
}
