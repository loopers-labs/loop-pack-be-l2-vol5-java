package com.loopers.domain.order;

import com.loopers.domain.common.PageNumber;
import com.loopers.domain.common.PageSize;
import com.loopers.domain.common.PageWindow;

import java.util.List;
import java.util.Optional;

public interface OrderRepository {

    Order save(Order order);

    Optional<Order> findById(Long orderId);

    Optional<Order> findByIdForUpdate(Long orderId);

    List<Order> findByUserId(Long userId);

    PageWindow<Order> findPage(Long userId, OrderStatus status, PageNumber page, PageSize size);
}
