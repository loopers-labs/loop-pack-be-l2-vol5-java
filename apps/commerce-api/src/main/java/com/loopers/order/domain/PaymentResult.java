package com.loopers.order.domain;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorCode;
import jakarta.persistence.Embeddable;

import java.time.ZonedDateTime;

@Embeddable
public class PaymentResult {

    private Long amount;
    private ZonedDateTime paidAt;

    protected PaymentResult() {
    }

    public PaymentResult(Long amount, ZonedDateTime paidAt) {
        if (amount == null || paidAt == null) {
            throw new CoreException(ErrorCode.INTERNAL_ERROR);
        }
        this.amount = amount;
        this.paidAt = paidAt;
    }

    public Long amount() {
        return amount;
    }

    public ZonedDateTime paidAt() {
        return paidAt;
    }
}
