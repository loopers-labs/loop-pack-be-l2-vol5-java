package com.loopers.application.payment;

public record PgPaymentResult(
    String transactionKey,
    String providerOrderId,
    long amount,
    PgStatus status
) {
    public enum PgStatus {
        PENDING,
        SUCCESS,
        FAILED,
        REJECTED,
        UNKNOWN
    }

    public boolean isSuccess() { return status == PgStatus.SUCCESS; }
    public boolean isFailed() { return status == PgStatus.FAILED; }
    public boolean isRejected() { return status == PgStatus.REJECTED; }
    public boolean isUnknown() { return status == PgStatus.UNKNOWN; }
    public boolean isPending() { return status == PgStatus.PENDING; }
}
