package com.loopers.interfaces.api.coupon.controller;

import com.loopers.application.coupon.support.CouponAdminAccess;
import com.loopers.application.coupon.support.CouponAdmissionControl;
import com.loopers.application.coupon.support.CouponExecution;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.coupon.dto.CouponAdminApiDto;
import com.loopers.interfaces.api.support.XUserId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/admin/coupon-admission")
@RequiredArgsConstructor
public class CouponAdmissionAdminController {
    private final CouponAdmissionControl control;

    @GetMapping
    public ApiResponse<CouponAdmissionControl.Status> status(@XUserId long userId) {
        CouponAdminAccess.requireAdmin(userId);
        return ApiResponse.success(control.status());
    }

    @PostMapping("/close")
    public ApiResponse<CouponAdmissionControl.Status> close(@XUserId long userId,
                                                          @RequestBody CouponAdminApiDto.CloseRequest request) {
        CouponAdminAccess.requireAdmin(userId);
        request.validate();
        control.close(request.reason());
        return ApiResponse.success(control.status());
    }

    @PostMapping("/verify-restart")
    public ApiResponse<CouponAdmissionControl.Status> verify(@XUserId long userId,
            @RequestBody CouponAdminApiDto.VerifyRestartRequest request) {
        CouponAdminAccess.requireAdmin(userId);
        List<CouponExecution.DbWork> sessions = request.validatedSessions();
        control.verifyRestart(request.expectedBootId(), request.previousProcessStopped(), request.auditReference(),
            request.initialDeployment(), sessions);
        return ApiResponse.success(control.status());
    }

    @PostMapping("/recover")
    public ApiResponse<List<CouponAdmissionControl.RecoveryResult>> recover(@XUserId long userId,
            @RequestBody CouponAdminApiDto.RecoverRequest request) {
        CouponAdminAccess.requireAdmin(userId);
        request.validate();
        return ApiResponse.success(control.recover(request.expectedBootId(), request.executionIds()));
    }

    @PostMapping("/open")
    public ApiResponse<CouponAdmissionControl.Status> open(@XUserId long userId,
            @RequestBody CouponAdminApiDto.BootRequest request) {
        CouponAdminAccess.requireAdmin(userId);
        request.validate();
        control.open(request.expectedBootId());
        return ApiResponse.success(control.status());
    }
}
