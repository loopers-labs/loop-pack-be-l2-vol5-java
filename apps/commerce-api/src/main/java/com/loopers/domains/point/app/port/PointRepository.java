package com.loopers.domains.point.app.port;

import com.loopers.domains.point.domain.Point;

public interface PointRepository {
    Point findOrCreateForUpdate(long userId);
    void save(Point point);
}
