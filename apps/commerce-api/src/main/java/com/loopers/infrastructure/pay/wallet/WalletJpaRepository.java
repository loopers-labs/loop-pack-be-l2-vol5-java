package com.loopers.infrastructure.pay.wallet;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

// 지갑 Spring Data JPA 레포지토리
public interface WalletJpaRepository extends JpaRepository<WalletJpaEntity, Long> {

    // 비관적 쓰기 잠금으로 조회
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select w from WalletJpaEntity w where w.userId = :userId")
    Optional<WalletJpaEntity> findByUserIdForUpdate(@Param("userId") long userId);
}
