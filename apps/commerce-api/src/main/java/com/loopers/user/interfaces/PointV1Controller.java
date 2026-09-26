package com.loopers.user.interfaces;

import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.RequestFields;
import com.loopers.interfaces.api.Requester;
import com.loopers.user.application.PointUseCase;
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

    private final PointUseCase pointUseCase;

    @PostMapping("/charge")
    public ApiResponse<PointV1Dto.BalanceResponse> charge(
        Requester requester,
        @RequestBody PointV1Dto.ChargeRequest request
    ) {
        long balance = pointUseCase.charge(requester.userId(), RequestFields.required(request.amount()));
        return ApiResponse.success(new PointV1Dto.BalanceResponse(balance));
    }

    @GetMapping
    public ApiResponse<PointV1Dto.BalanceResponse> getBalance(Requester requester) {
        return ApiResponse.success(new PointV1Dto.BalanceResponse(pointUseCase.getBalance(requester.userId())));
    }
}
