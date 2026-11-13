package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssueDeadLetters;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api-admin/v1/coupon-issues/dead-letters")
@RequiredArgsConstructor
public class CouponIssueDeadLetterAdminV1Controller {

    private final CouponIssueDeadLetters couponIssueDeadLetters;

    @GetMapping
    public ApiResponse<List<CouponIssueDeadLetterAdminV1Dto.DeadLetterResponse>> findRecent() {
        return ApiResponse.success(
            CouponIssueDeadLetterAdminV1Dto.DeadLetterResponse.listOf(couponIssueDeadLetters.findRecent()));
    }

    @PostMapping("/replay")
    public ApiResponse<CouponIssueDeadLetterAdminV1Dto.ReplayResponse> replay(
        @RequestBody CouponIssueDeadLetterAdminV1Dto.ReplayRequest request) {
        return ApiResponse.success(
            new CouponIssueDeadLetterAdminV1Dto.ReplayResponse(couponIssueDeadLetters.replay(request.ids())));
    }
}
