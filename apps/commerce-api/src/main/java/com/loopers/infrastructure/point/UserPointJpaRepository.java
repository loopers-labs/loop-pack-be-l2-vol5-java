package com.loopers.infrastructure.point;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

interface UserPointJpaRepository extends JpaRepository<UserPointEntity, Long> {

    Optional<UserPointEntity> findByUserId(Long userId);

    @Modifying
    @Query(value = """
        INSERT INTO user_point (user_id, balance, created_at, updated_at)
        VALUES (:userId, 0, NOW(6), NOW(6))
        ON DUPLICATE KEY UPDATE user_id = user_id
        """, nativeQuery = true)
    int lockOrOpen(@Param("userId") Long userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from UserPointEntity p where p.userId = :userId")
    Optional<UserPointEntity> findByUserIdForUpdate(@Param("userId") Long userId);
}
