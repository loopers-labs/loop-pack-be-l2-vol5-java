package com.loopers.application.pay.usecase;

import com.loopers.application.pay.command.WalletCommand;
import com.loopers.application.pay.result.WalletResult;

// 지갑 충전 유스케이스
public interface ChargeWalletUseCase {
    WalletResult execute(WalletCommand.Charge command);
}
