package com.loopers.user.domain;

import java.util.Optional;

public interface PointRepository {
    Point save(Point point);
    Optional<Point> findByUserId(Long userId);
    Optional<Point> findForOrderByUserId(Long userId);
}
