package com.loopers.application.pay.wallet;

// 지갑 충전 유스케이스
public interface ChargeWalletUseCase {
    WalletResult execute(WalletCommand.Charge command);
}
