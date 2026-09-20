package com.loopers.application.point;

import com.loopers.domain.point.PointModel;

/** 설계 4-3-0 PointBalance. */
public record PointInfo(Long userId, Long balance) {
    public static PointInfo from(PointModel point) {
        return new PointInfo(point.getUserId(), point.getBalance());
    }
}
