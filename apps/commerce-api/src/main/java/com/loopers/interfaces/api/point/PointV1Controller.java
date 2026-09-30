package com.loopers.interfaces.api.point;

import com.loopers.application.user.PointInfo;
import com.loopers.application.user.UserFacade;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointV1Controller implements PointV1ApiSpec {

    private final UserFacade userFacade;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> chargePoint(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        PointInfo info = userFacade.chargePoint(userId, request.amount());
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }

    @GetMapping
    @Override
    public ApiResponse<PointV1Dto.PointResponse> getBalance(
        @RequestHeader("X-USER-ID") Long userId
    ) {
        PointInfo info = userFacade.getBalance(userId);
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }
}
