package com.loopers.domain.point;

import java.time.ZonedDateTime;
import java.util.Optional;

public interface PointRepository {
    Optional<Point> findByUserId(Long userId);

    Point save(Point point);

    int increaseBalanceIfWithinMaximum(Long userId, long amount, long maximumBalance, ZonedDateTime updatedAt);

    int decreaseBalanceIfEnough(Long userId, long amount, ZonedDateTime updatedAt);
}
