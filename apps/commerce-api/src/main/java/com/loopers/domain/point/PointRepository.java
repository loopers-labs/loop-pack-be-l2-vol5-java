package com.loopers.domain.point;

import java.util.Optional;

public interface PointRepository {
    Optional<PointModel> findByUserId(Long userId);

    /**
     * 변경을 위해 사용자의 Point 행을 비관적 쓰기 잠금으로 조회한다.
     * 잠금은 호출한 트랜잭션이 끝날 때까지 유지된다. 잔액 조회는 잠그지 않는 {@link #findByUserId} 를 사용한다.
     */
    Optional<PointModel> findByUserIdForUpdate(Long userId);

    PointModel save(PointModel point);
}
