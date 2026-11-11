package com.loopers.domains.coupon.app;

import com.loopers.common.error.TemporarilyUnavailableException;

public class AdmissionClosedException extends TemporarilyUnavailableException {
    public AdmissionClosedException(String message) {
        super(message);
    }
}
