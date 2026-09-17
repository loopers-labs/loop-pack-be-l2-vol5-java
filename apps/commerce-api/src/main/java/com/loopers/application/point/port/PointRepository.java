package com.loopers.application.point.port;

import com.loopers.domain.point.Point;

public interface PointRepository {
    Point findOrCreateForUpdate(long userId);
    void save(Point point);
}
