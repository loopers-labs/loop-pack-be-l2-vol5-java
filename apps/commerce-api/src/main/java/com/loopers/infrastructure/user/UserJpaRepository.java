package com.loopers.infrastructure.user;

import com.loopers.domain.user.UserModel;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<UserModel, Long> {

    /**
     * 비관적 락으로 조회한다 — 주문 확정 시 포인트 차감 전에만 쓴다.
     * (docs/week2/design.md 5번 섹션 "차감 → 비관적 락" 참고)
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM UserModel u WHERE u.id = :id")
    Optional<UserModel> findForUpdate(@Param("id") Long id);

    /**
     * 읽고-검증-저장 없이 DB에서 원자적으로 잔액을 더한다 — 동시 충전 간 lost-update를 막는다.
     * (docs/week2/design.md 5번 섹션 "증가(Point.charge()) → 원자적 UPDATE" 참고)
     */
    @Modifying(clearAutomatically = true)
    @Transactional
    @Query("UPDATE UserModel u SET u.point.balance = u.point.balance + :amount WHERE u.id = :id")
    int chargePoint(@Param("id") Long id, @Param("amount") long amount);
}
