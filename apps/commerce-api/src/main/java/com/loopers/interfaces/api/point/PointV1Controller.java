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

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final PointFacade pointFacade;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointV1Dto.PointResponse> charge(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        PointInfo info = pointFacade.charge(requireUserId(userId), request.amount());
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }

    @GetMapping
    @Override
    public ApiResponse<PointV1Dto.PointResponse> getPoint(
        @RequestHeader(value = USER_ID_HEADER, required = false) Long userId
    ) {
        PointInfo info = pointFacade.getPoint(requireUserId(userId));
        return ApiResponse.success(PointV1Dto.PointResponse.from(info));
    }

    private Long requireUserId(Long userId) {
        if (userId == null) {
            throw new CoreException(ErrorType.BAD_REQUEST, USER_ID_HEADER + " 헤더가 필요합니다.");
        }
        return userId;
    }
}
