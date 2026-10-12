package com.loopers.point.adapter.in.web;

import com.loopers.point.adapter.in.web.dto.PointDto;
import com.loopers.point.adapter.in.web.spec.PointApiSpec;
import com.loopers.point.application.PointQueryService;
import com.loopers.point.application.port.in.PointCommandUseCase;
import com.loopers.support.web.ApiResponse;
import com.loopers.user.adapter.in.web.LoginUser;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/points")
public class PointController implements PointApiSpec {

    private final PointCommandUseCase pointCommandUseCase;
    private final PointQueryService pointQueryService;

    @PostMapping("/charge")
    @Override
    public ApiResponse<PointDto.BalanceResponse> charge(LoginUser loginUser, @RequestBody PointDto.ChargeRequest request) {
        return ApiResponse.success(PointDto.BalanceResponse.from(pointCommandUseCase.charge(loginUser.id(), request.requiredAmount())));
    }

    @GetMapping
    @Override
    public ApiResponse<PointDto.BalanceResponse> getBalance(LoginUser loginUser) {
        return ApiResponse.success(PointDto.BalanceResponse.from(pointQueryService.getBalance(loginUser.id())));
    }
}
