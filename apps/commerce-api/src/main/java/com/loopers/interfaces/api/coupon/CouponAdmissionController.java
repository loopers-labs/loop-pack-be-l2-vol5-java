package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.CouponFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
public class CouponAdmissionController implements CouponAdmissionApiSpec {

    private final CouponFacade couponFacade;

    @GetMapping("/api/coupon/admission")
    @Override
    public ApiResponse<CouponAdmissionDto.AdmissionResponse> status() {
        return ApiResponse.success(new CouponAdmissionDto.AdmissionResponse(couponFacade.status()));
    }

    @PostMapping("/api/coupon/admission/close")
    @Override
    public ApiResponse<CouponAdmissionDto.AdmissionResponse> close() {
        return ApiResponse.success(new CouponAdmissionDto.AdmissionResponse(couponFacade.close()));
    }

    @PostMapping("/api/coupon/admission/open")
    @Override
    public ApiResponse<CouponAdmissionDto.AdmissionResponse> open() {
        return ApiResponse.success(new CouponAdmissionDto.AdmissionResponse(couponFacade.open()));
    }
}
