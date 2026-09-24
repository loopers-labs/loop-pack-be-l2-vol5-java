package com.loopers.interfaces.api.pay.wallet;

import com.loopers.application.pay.wallet.WalletCommand;
import com.loopers.application.pay.wallet.WalletResult;
import com.loopers.domain.support.error.DomainErrorCode;
import com.loopers.domain.support.error.DomainException;

// 지갑 API 요청/응답 DTO 모음
public final class WalletApiDto {
    private WalletApiDto() {}

    // 지갑 충전 요청
    public record ChargeRequest(Long amount) {
        // 커맨드로 변환
        public WalletCommand.Charge toCommand(long userId) {
            if (amount == null) {
                throw new DomainException(DomainErrorCode.NON_POSITIVE_MONEY);
            }
            return new WalletCommand.Charge(userId, amount);
        }
    }

    // 지갑 잔액 응답
    public record BalanceResponse(long balance) {
        public static BalanceResponse from(WalletResult result) {
            return new BalanceResponse(result.balance());
        }

        public static BalanceResponse from(long balance) {
            return new BalanceResponse(balance);
        }
    }
}
