package com.loopers.application.point;

public record PointInfo(Long userId, Long balance) {
    public static PointInfo of(Long userId, long balance) {
        return new PointInfo(userId, balance);
    }
}
