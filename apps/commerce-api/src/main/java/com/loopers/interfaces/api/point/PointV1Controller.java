package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
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
public class PointV1Controller {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final PointFacade pointFacade;

    @PostMapping("/charge")
    public ApiResponse<PointV1Dto.PointResponse> charge(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        if (request.amount() == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, "충전 금액은 필수입니다.");
        }

        long balance = pointFacade.charge(userId, request.amount());
        return ApiResponse.success(new PointV1Dto.PointResponse(balance));
    }

    @GetMapping
    public ApiResponse<PointV1Dto.PointResponse> getBalance(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId
    ) {
        long balance = pointFacade.getBalance(userId);
        return ApiResponse.success(new PointV1Dto.PointResponse(balance));
    }
}
