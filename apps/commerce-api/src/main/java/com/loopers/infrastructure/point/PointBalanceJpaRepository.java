package com.loopers.infrastructure.point;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PointBalanceJpaRepository extends JpaRepository<PointBalanceJpaEntity, Long> {
    Optional<PointBalanceJpaEntity> findByUserId(long userId);
}
