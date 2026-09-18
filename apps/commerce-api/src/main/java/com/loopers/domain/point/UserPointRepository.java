package com.loopers.domain.point;

import java.util.List;
import java.util.Optional;

public interface UserPointRepository {

    UserPoint loadForUpdate(Long userId);

    Optional<UserPoint> findForUpdate(Long userId);

    Optional<UserPoint> findByUserId(Long userId);

    UserPoint save(UserPoint userPoint);

    List<PointTransaction> findTransactions(Long userId);
}
