package com.loopers.infrastructure.user;

import com.loopers.domain.user.User;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserJpaRepository extends JpaRepository<User, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from User user where user.id = :userId")
    Optional<User> lockById(@Param("userId") long userId);

    @Modifying
    @Query(value = """
        INSERT INTO `user` (id, point_balance, created_at, updated_at)
        VALUES (:userId, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
        ON DUPLICATE KEY UPDATE id = id
        """, nativeQuery = true)
    void insertIfMissing(@Param("userId") long userId);
}
