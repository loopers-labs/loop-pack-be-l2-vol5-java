package com.loopers.domain.point;

import java.util.Optional;

public interface PointBalanceRepository {
    Optional<PointBalance> findByUserId(long userId);
    PointBalance save(PointBalance balance);
}
