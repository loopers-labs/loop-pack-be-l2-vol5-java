package com.loopers.user.interfaces;

public class PointV1Dto {

    public record ChargeRequest(Long amount) {
    }

    public record BalanceResponse(long balance) {
    }
}
