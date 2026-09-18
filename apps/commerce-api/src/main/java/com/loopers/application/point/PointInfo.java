package com.loopers.application.point;

import com.loopers.domain.point.Point;

public record PointInfo(long balance) {
    public static PointInfo from(Point point) {
        return new PointInfo(point.getBalance());
    }
}
