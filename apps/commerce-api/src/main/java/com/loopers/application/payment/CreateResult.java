package com.loopers.application.payment;

import com.loopers.domain.payment.CreateObservation;

import java.util.List;

final class CreateResult {

    private static final String ACCEPTED = "accepted";
    private static final String REJECTED = "rejected";
    private static final String NOT_SENT = "not_sent";
    private static final String UNKNOWN = "unknown";

    private CreateResult() {
    }

    static List<String> names() {
        return List.of(ACCEPTED, REJECTED, NOT_SENT, UNKNOWN);
    }

    static String nameOf(CreateObservation observation) {
        return switch (observation) {
            case CreateObservation.Accepted accepted -> ACCEPTED;
            case CreateObservation.RejectedNoEffect rejected -> REJECTED;
            case CreateObservation.NotSent notSent -> NOT_SENT;
            case CreateObservation.CreateUnknown unknown -> UNKNOWN;
        };
    }
}
