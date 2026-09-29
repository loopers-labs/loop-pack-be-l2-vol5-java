package com.loopers.interfaces.api.point;

public class PointV1Dto {
    public record ChargeRequest(Long amount) {}

    public record PointResponse(long balance) {}
}
