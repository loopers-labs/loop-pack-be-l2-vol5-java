package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponIssuanceService;
import com.loopers.application.coupon.OwnedCouponInfo;
import com.loopers.domain.common.PageResult;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.commerce.StrictInput;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class OwnedCouponController {
    private final CouponIssuanceService issuance;

    public OwnedCouponController(CouponIssuanceService issuance) {
        this.issuance = issuance;
    }

    @GetMapping(value = "/api/v1/coupons", produces = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<PageResult<OwnedCouponInfo>> list(
        @RequestHeader(value = "X-USER-ID", required = false) String requester,
        @RequestParam(required = false) String page,
        @RequestParam(required = false) String size
    ) {
        return ApiResponse.success(issuance.pageIssued(requester, StrictInput.page(page), StrictInput.size(size)));
    }
}
