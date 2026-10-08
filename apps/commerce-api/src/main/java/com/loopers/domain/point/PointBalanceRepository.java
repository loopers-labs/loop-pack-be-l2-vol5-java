package com.loopers.domain.point;

import java.util.Optional;

public interface PointBalanceRepository {
    Optional<PointBalance> findByUserId(long userId);

    /**
     * 객체가 가진 잔액을 그대로 저장한다. 동시 변경을 보호하지 않는다.
     * 운영 충전과 결제에는 charge와 deduct를 사용한다.
     */
    PointBalance save(PointBalance balance);

    /**
     * 잔액 행이 없으면 초기화하고 현재 DB 잔액에 양수 금액을 더한 결과를 반환한다.
     * 잘못된 금액과 합산 범위 초과는 INVALID_REQUEST로 거절한다.
     */
    PointBalance charge(long userId, long amount);

    /**
     * 현재 DB 잔액에서 양수 금액을 차감한다.
     * 잘못된 금액은 INVALID_REQUEST, 잔액 행이 없거나 부족하면 INSUFFICIENT_POINTS로 거절한다.
     */
    void deduct(long userId, long amount);
}
