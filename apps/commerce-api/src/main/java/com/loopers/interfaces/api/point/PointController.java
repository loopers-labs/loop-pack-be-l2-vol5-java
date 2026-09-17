package com.loopers.interfaces.api.point;

import com.loopers.application.point.PointFacade;
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
public class PointController implements PointApiSpec {

    private static final String USER_ID_HEADER = "X-USER-ID";

    private final PointFacade pointFacade;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointDto.BalanceResponse> charge(
        @RequestHeader(USER_ID_HEADER) Long userId,
        @RequestBody PointDto.ChargeRequest request
    ) {
        return ApiResponse.success(PointDto.BalanceResponse.from(pointFacade.charge(userId, request.amount())));
    }

    @GetMapping
    @Override
    public ApiResponse<PointDto.BalanceResponse> getBalance(
        @RequestHeader(USER_ID_HEADER) Long userId
    ) {
        return ApiResponse.success(PointDto.BalanceResponse.from(pointFacade.getBalance(userId)));
    }
}
