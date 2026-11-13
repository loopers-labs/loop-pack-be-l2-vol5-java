package com.loopers.interfaces.api.coupon.controller;

import com.loopers.application.coupon.service.CouponCounterManagementService;
import com.loopers.application.coupon.support.CouponAdminAccess;
import com.loopers.application.coupon.support.CouponCounterException;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.RequestInputValidator;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
@RequestMapping("/api/v1/admin/coupon-campaigns/{campaignId}/counter")
@RequiredArgsConstructor
public class CouponCounterAdminController {
    public record CounterView(long databaseCount, boolean prepared, Long aggregateCount) {}
    public record RebuildView(long aggregateCount) {}
    private final CouponCounterManagementService management;

    @GetMapping
    public ApiResponse<CounterView> compare(@XUserId long userId, @PathVariable long campaignId) {
        authorize(userId, campaignId);
        try {
            CouponCounterManagementService.Comparison result = management.compare(campaignId);
            return ApiResponse.success(new CounterView(result.databaseCount(), result.aggregateCount().isPresent(),
                result.aggregateCount().isPresent() ? result.aggregateCount().getAsLong() : null));
        } catch (DataAccessException failure) {
            log.warn("coupon campaign={} stage=COUNTER_ADMIN_COMPARE_FAILED", campaignId, failure);
            throw unavailable();
        }
    }

    @PostMapping("/rebuild")
    public ApiResponse<RebuildView> rebuild(@XUserId long userId, @PathVariable long campaignId) {
        authorize(userId, campaignId);
        try {
            return ApiResponse.success(new RebuildView(management.rebuild(campaignId)));
        } catch (DataAccessException failure) {
            log.warn("coupon campaign={} stage=COUNTER_ADMIN_REBUILD_FAILED", campaignId, failure);
            throw unavailable();
        }
    }

    private static void authorize(long userId, long campaignId) {
        CouponAdminAccess.requireAdmin(userId);
        RequestInputValidator.requirePositiveId(campaignId, "캠페인 ID");
    }

    private static CouponCounterException unavailable() {
        return new CouponCounterException("쿠폰 집계를 확인할 수 없습니다. DB 발급 결과를 보존하고 재집계 상태를 확인하세요.");
    }
}
