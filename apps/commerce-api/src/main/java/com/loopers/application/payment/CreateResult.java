package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;

final class CreateResult {

    private CreateResult() {
    }

    static String nameOf(CreateObservation observation) {
        return switch (observation) {
            case CreateObservation.Accepted accepted -> "accepted";
            case CreateObservation.RejectedNoEffect rejected -> "rejected";
            case CreateObservation.NotSent notSent -> "not_sent";
            case CreateObservation.CreateUnknown unknown -> "unknown";
        };
    }
}
