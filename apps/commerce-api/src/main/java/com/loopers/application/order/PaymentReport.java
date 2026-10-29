package com.loopers.application.order;

import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentTransaction;
import com.loopers.domain.order.Order;
import com.loopers.domain.payment.PaymentRecord;

// PG가 보고한 결과 하나. create 응답뿐 아니라 이후 조회·callback 결과도 이 모양으로 결과 반영 경계에 들어온다.
public record PaymentReport(PaymentRecord.ProviderStatus status, String transactionKey) {
    public PaymentReport {
        if (status == null || status == PaymentRecord.ProviderStatus.SEND_STARTED) {
            throw new IllegalArgumentException("PG 보고에는 발송 시작이 아닌 PG 상태가 필요합니다.");
        }
    }

    public static PaymentReport from(PaymentCreateResult result) {
        return switch (result) {
            case PaymentCreateResult.Accepted accepted -> from(accepted.transaction());
            case PaymentCreateResult.RejectedNoEffect rejected ->
                new PaymentReport(PaymentRecord.ProviderStatus.REJECTED_NO_EFFECT, null);
            case PaymentCreateResult.NotSent notSent -> new PaymentReport(PaymentRecord.ProviderStatus.NOT_SENT, null);
            case PaymentCreateResult.Unknown unknown -> new PaymentReport(PaymentRecord.ProviderStatus.UNKNOWN, null);
        };
    }

    public static PaymentReport from(PaymentTransaction transaction) {
        PaymentRecord.ProviderStatus status = switch (transaction.status()) {
            case PENDING -> PaymentRecord.ProviderStatus.PENDING;
            case SUCCESS -> PaymentRecord.ProviderStatus.SUCCESS;
            case FAILED -> PaymentRecord.ProviderStatus.FAILED;
        };
        return new PaymentReport(status, transaction.transactionKey());
    }

    Order.PaymentOutcome outcome() {
        return switch (status) {
            case SUCCESS -> Order.PaymentOutcome.SUCCEEDED;
            case FAILED, REJECTED_NO_EFFECT, ALL_NOT_SENT -> Order.PaymentOutcome.FAILED;
            case SEND_STARTED, NOT_SENT, PENDING, UNKNOWN, NEEDS_REVIEW -> Order.PaymentOutcome.UNKNOWN;
        };
    }
}
