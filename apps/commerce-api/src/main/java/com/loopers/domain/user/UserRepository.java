package com.loopers.domain.user;

import java.util.Optional;

public interface UserRepository {
    Optional<UserModel> find(Long id);

    /**
     * 비관적 락(SELECT ... FOR UPDATE)으로 조회한다 — 주문 확정 시 포인트 차감 전에만 쓴다.
     * (docs/week2/design.md 5번 섹션 "차감 → 비관적 락" 참고)
     */
    Optional<UserModel> findForUpdate(Long id);

    int chargePoint(Long id, long amount);
}
