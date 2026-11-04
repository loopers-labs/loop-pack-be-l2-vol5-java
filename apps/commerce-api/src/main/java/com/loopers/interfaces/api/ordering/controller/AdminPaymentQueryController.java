package com.loopers.interfaces.api.ordering.controller;

import com.loopers.application.common.PageCriteria;
import com.loopers.application.common.PageResult;
import com.loopers.application.ordering.query.AdminOrderView;
import com.loopers.application.ordering.query.OrderQueryDao;
import com.loopers.application.ordering.support.OrderAdminAccess;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api-admin/v1/payments")
@RequiredArgsConstructor
public class AdminPaymentQueryController {
    private final OrderQueryDao orderQueryDao;

    // 목록 조회는 실행 중인 작업을 인계하거나 PG를 호출하지 않는다.
    @GetMapping("/incomplete")
    public ApiResponse<PageResult<AdminOrderView>> findIncomplete(
            @XUserId long userId, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        OrderAdminAccess.requireAdmin(userId);
        return ApiResponse.success(orderQueryDao.findIncompletePayments(new PageCriteria(page, size)));
    }
}
