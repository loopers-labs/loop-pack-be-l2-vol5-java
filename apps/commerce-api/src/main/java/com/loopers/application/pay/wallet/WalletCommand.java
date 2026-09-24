package com.loopers.application.pay.wallet;

// 지갑 관련 커맨드 모음
public final class WalletCommand {
    private WalletCommand() {}

    public record Charge(long userId, long amount) {}
}
