package com.loopers.interfaces.api.point;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.loopers.application.point.PointInfo;
import com.loopers.interfaces.api.serialization.ExactLongDeserializer;

public class PointV1Dto {
    public record BalanceResponse(long balance) {
        public static BalanceResponse from(PointInfo info) {
            return new BalanceResponse(info.balance());
        }
    }

    public record ChargeRequest(
        @JsonDeserialize(using = ExactLongDeserializer.class) Long amount
    ) {}

    public record ChargeResponse(long balance) {
        public static ChargeResponse from(PointInfo info) {
            return new ChargeResponse(info.balance());
        }
    }
}
