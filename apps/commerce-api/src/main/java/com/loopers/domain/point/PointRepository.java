package com.loopers.domain.point;

import java.util.Optional;

public interface PointRepository {
    PointModel save(PointModel point);

    Optional<PointModel> findByUserId(Long userId);

    /** 차감용: 비관적 쓰기 락(SELECT ... FOR UPDATE) (DR-34). */
    Optional<PointModel> findByUserIdForUpdate(Long userId);
}
