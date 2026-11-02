package com.loopers.point.app.port;

import com.loopers.point.domain.Point;

public interface PointRepository {
    Point findOrCreateForUpdate(long userId);
    void save(Point point);
}
