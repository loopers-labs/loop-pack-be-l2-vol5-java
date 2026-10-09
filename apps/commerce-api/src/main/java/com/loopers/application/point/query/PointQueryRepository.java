package com.loopers.application.point.query;

import java.util.Optional;

/** 포인트 조회 전용 Repository. {@link PointView} 로 바로 반환한다 (DR-31). */
public interface PointQueryRepository {
    /** FR-POINT-02: 사용자의 포인트 계정 잔액. */
    Optional<PointView.Balance> findBalanceByUserId(Long userId);
}
