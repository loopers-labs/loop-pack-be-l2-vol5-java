package com.loopers.interfaces.api.ordering.controller;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.ordering.query.OrderQueryDao;
import com.loopers.application.ordering.query.OrderView;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.RequestInputValidator;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
// 사용자 주문 조회 컨트롤러
public class OrderQueryController {
    private final OrderQueryDao orderQueryDao;

    // 내 주문 목록 페이지 조회
    @GetMapping
    public ApiResponse<PageResult<OrderView>> findAll(
        @XUserId long userId,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(orderQueryDao.findOrders(userId, new PageCriteria(page, size)));
    }

    // 단건 주문 조회
    @GetMapping("/{orderId}")
    public ApiResponse<OrderView> find(@PathVariable long orderId) {
        RequestInputValidator.requirePositiveId(orderId, "주문 ID");
        OrderView order = orderQueryDao.findOrder(orderId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
        return ApiResponse.success(order);
    }
}
