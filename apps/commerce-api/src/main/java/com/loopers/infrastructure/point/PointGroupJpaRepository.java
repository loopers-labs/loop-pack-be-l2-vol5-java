package com.loopers.infrastructure.point;

import com.loopers.domain.common.Money;
import com.loopers.domain.point.PointGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface PointGroupJpaRepository extends JpaRepository<PointGroup, Long> {

    @Query("SELECT g FROM PointGroup g WHERE g.userId = :userId AND g.remaining > :zero ORDER BY g.expiresAt ASC, g.id ASC")
    List<PointGroup> findRemainingByUserId(@Param("userId") Long userId, @Param("zero") Money zero);
}
