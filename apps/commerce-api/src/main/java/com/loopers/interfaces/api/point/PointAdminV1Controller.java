package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api-admin/v1/points")
public class PointAdminV1Controller implements PointAdminV1ApiSpec {

    private final PointFacade pointFacade;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> charge(
        @RequesterId Long requesterId,
        @RequestBody PointAdminV1Dto.AdjustRequest request
    ) {
        var info = pointFacade.chargeByAdmin(requesterId, request.userId(), request.amount());
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }

    @PostMapping("/deduct")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> deduct(
        @RequesterId Long requesterId,
        @RequestBody PointAdminV1Dto.AdjustRequest request
    ) {
        var info = pointFacade.deductByAdmin(requesterId, request.userId(), request.amount());
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }
}
