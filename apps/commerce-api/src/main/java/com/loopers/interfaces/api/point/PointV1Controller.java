package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.auth.RequesterId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointV1Controller implements PointV1ApiSpec {

    private final PointFacade pointFacade;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> charge(
        @RequesterId Long requesterId,
        @RequestBody PointV1Dto.AmountRequest request
    ) {
        return ApiResponse.success(PointV1Dto.PointResponse.from(pointFacade.charge(requesterId, request.amount())));
    }

    @GetMapping
    @Override
    public ApiResponse<PointV1Dto.PointResponse> getBalance(@RequesterId Long requesterId) {
        return ApiResponse.success(PointV1Dto.PointResponse.from(pointFacade.getBalance(requesterId)));
    }

    @PostMapping("/refund")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> refund(
        @RequesterId Long requesterId,
        @RequestBody PointV1Dto.AmountRequest request
    ) {
        return ApiResponse.success(PointV1Dto.PointResponse.from(pointFacade.refund(requesterId, request.amount())));
    }
}
