package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.application.point.PointInfo;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
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

    private final PointFacade pointFacade;

    @GetMapping
    @Override
    public ApiResponse<PointV1Dto.BalanceResponse> getBalance(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId
    ) {
        validateUserId(userId);

        PointInfo info = pointFacade.getBalance(userId);
        return ApiResponse.success(PointV1Dto.BalanceResponse.from(info));
    }

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointV1Dto.ChargeResponse> charge(
        @RequestHeader(value = "X-USER-ID", required = false) Long userId,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        validateUserId(userId);
        if (request.amount() == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전액이 필요합니다.");
        }

        PointInfo info = pointFacade.charge(userId, request.amount());
        return ApiResponse.success(PointV1Dto.ChargeResponse.from(info));
    }

    private void validateUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "요청자 식별값이 필요합니다.");
        }
    }
}
