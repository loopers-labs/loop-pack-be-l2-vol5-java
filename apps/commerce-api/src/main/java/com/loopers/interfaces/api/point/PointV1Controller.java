package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
import com.loopers.domain.point.PointService;
import com.loopers.interfaces.api.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
public class PointV1Controller {

    private final PointFacade pointFacade;
    private final PointService pointService;

    @PostMapping("/charge")
    public ApiResponse<PointV1Dto.BalanceResponse> charge(
        @RequestHeader("X-USER-ID") Long userId,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        return ApiResponse.success(PointV1Dto.BalanceResponse.from(
            pointFacade.charge(userId, request.toAmount(), Instant.now())));
    }

    @GetMapping
    public ApiResponse<PointV1Dto.BalanceResponse> getBalance(@RequestHeader("X-USER-ID") Long userId) {
        return ApiResponse.success(PointV1Dto.BalanceResponse.from(pointService.getBalance(userId)));
    }
}
