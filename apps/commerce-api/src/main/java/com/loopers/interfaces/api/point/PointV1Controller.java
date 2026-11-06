package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.LoginUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointV1Controller {

    private final PointFacade pointFacade;

    @GetMapping
    public ApiResponse<PointV1Dto.PointResponse> getPoint(@LoginUserId Long userId) {
        var info = pointFacade.getPoint(userId);
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }

    @PostMapping("/charge")
    public ApiResponse<PointV1Dto.PointResponse> charge(
        @LoginUserId Long userId,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        var info = pointFacade.charge(userId, request.amount());
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }
}
