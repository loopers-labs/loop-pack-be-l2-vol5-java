package com.loopers.interfaces.api.pay.wallet;

import com.loopers.application.pay.wallet.ChargeWalletUseCase;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
// 지갑 충전 API 컨트롤러
public class WalletController {
    private final ChargeWalletUseCase chargeWalletUseCase;

    // 지갑 충전 요청 처리
    @PostMapping("/charge")
    public ApiResponse<WalletApiDto.BalanceResponse> charge(
        @XUserId long userId,
        @RequestBody WalletApiDto.ChargeRequest request
    ) {
        return ApiResponse.success(WalletApiDto.BalanceResponse.from(chargeWalletUseCase.execute(request.toCommand(userId))));
    }
}
