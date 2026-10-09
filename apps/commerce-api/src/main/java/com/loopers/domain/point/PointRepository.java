package com.loopers.domain.point;

import java.util.Optional;

public interface PointRepository {
    Optional<PointModel> findByUserId(Long userId);

    Optional<PointModel> findByUserIdWithLock(Long userId);

    PointModel save(PointModel point);
}
