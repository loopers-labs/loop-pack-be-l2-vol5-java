package com.loopers.domain.point;

import java.util.Optional;

public interface PointRepository {
    Point save(Point point);

    Optional<Point> findByUserId(Long userId);

    /**
     * 잔액이 충분할 때만 balance = balance - amount 로 차감한다.
     * Point 가 메모리에서 하던 "잔액 부족 → 거절" 상태 검증과 차감을 WHERE 조건부 UPDATE 로 대신한다.
     *
     * @return 갱신된 행 수. 0 이면 잔액 부족이거나 포인트 행이 없다.
     */
    int deductIfEnough(Long userId, long amount);

    /**
     * balance = balance + amount 로 누적한다. 표현 범위를 넘게 되면 갱신하지 않는다.
     * Point.charge 가 메모리에서 하던 누적과 "표현 범위 초과 → 거절" 검증을 WHERE 조건부 UPDATE 로 대신한다.
     *
     * @return 갱신된 행 수. 0 이면 포인트 행이 없거나 표현 범위를 넘는다.
     */
    int addBalance(Long userId, long amount);
}
