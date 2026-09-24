package com.loopers.interfaces.api.pay.wallet;

import com.loopers.application.pay.wallet.WalletQueryDao;
import com.loopers.interfaces.api.ApiResponse;
import com.loopers.interfaces.api.support.XUserId;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/points")
@RequiredArgsConstructor
// 지갑 조회 API 컨트롤러
public class WalletQueryController {
    private final WalletQueryDao walletQueryDao;

    // 사용자 지갑 잔액 조회
    @GetMapping
    public ApiResponse<WalletApiDto.BalanceResponse> findBalance(@XUserId long userId) {
        return ApiResponse.success(WalletApiDto.BalanceResponse.from(walletQueryDao.findBalance(userId).orElseThrow()));
    }
}
