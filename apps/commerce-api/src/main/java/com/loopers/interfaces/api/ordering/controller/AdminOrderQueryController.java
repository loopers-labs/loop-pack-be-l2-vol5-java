package com.loopers.interfaces.api.ordering.controller;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderQueryDao;
import com.loopers.application.support.error.ApplicationErrorCode;
import com.loopers.application.support.error.ApplicationException;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.RequestInputValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api-admin/v1/orders")
@RequiredArgsConstructor
// 관리자용 주문 조회 컨트롤러
public class AdminOrderQueryController {
    private final OrderQueryDao orderQueryDao;

    // 전체 주문 목록 페이지 조회
    @GetMapping
    public ApiResponse<PageResult<AdminOrderView>> findAll(
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(orderQueryDao.findAdminOrders(new PageCriteria(page, size)));
    }

    // 단건 주문 조회
    @GetMapping("/{orderId}")
    public ApiResponse<AdminOrderView> find(@PathVariable long orderId) {
        RequestInputValidator.requirePositiveId(orderId, "주문 ID");
        AdminOrderView order = orderQueryDao.findAdminOrder(orderId)
            .orElseThrow(() -> new ApplicationException(ApplicationErrorCode.ORDER_NOT_FOUND));
        return ApiResponse.success(order);
    }
}
