package com.loopers.domain.point;

import java.util.Optional;
import java.util.List;

public interface PointBalanceRepository {
    List<Long> findUserIdsAfter(Long afterUserId, int limit);

    PointBalanceModel save(PointBalanceModel pointBalance);

    Optional<PointBalanceModel> findByUserIdForUpdate(Long userId);
}
