package com.loopers.interfaces.api.point;

import com.loopers.domain.point.PointChange;
import com.loopers.domain.point.PointModel;
import com.loopers.domain.point.PointService;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.CustomerId;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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

    private final PointService pointService;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> charge(
        @CustomerId Long userId,
        @RequestBody(required = false) PointV1Dto.PointChargeRequest request
    ) {
        if (request == null || request.amount() == null) {
            throw new CoreException(ErrorType.INVALID_POINT_AMOUNT);
        }

        PointChange change = pointService.charge(userId, request.amount());
        return ApiResponse.success(PointV1Dto.PointResponse.of(change.afterBalance()));
    }

    @GetMapping
    @Override
    public ApiResponse<PointV1Dto.PointResponse> getBalance(@CustomerId Long userId) {
        PointModel point = pointService.getPoint(userId);
        return ApiResponse.success(PointV1Dto.PointResponse.from(point));
    }
}
