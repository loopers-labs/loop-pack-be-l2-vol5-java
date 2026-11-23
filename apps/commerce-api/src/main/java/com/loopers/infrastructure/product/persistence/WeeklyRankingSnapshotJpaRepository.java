package com.loopers.infrastructure.product.persistence;

import com.loopers.domain.product.entity.WeeklyRankingSnapshot;
import com.loopers.domain.product.enums.RankingSnapshotStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyRankingSnapshotJpaRepository extends JpaRepository<WeeklyRankingSnapshot, Long> {

    /** 기간마다 PUBLISHED 는 하나다 (9주차 D-180 의 유일 인덱스) */
    Optional<WeeklyRankingSnapshot> findByPeriodStartAndStatus(LocalDate periodStart, RankingSnapshotStatus status);
}
