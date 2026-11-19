package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingPeriod;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyRankingPeriodJpaRepository extends JpaRepository<WeeklyRankingPeriod, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WeeklyRankingPeriod p where p.periodStart = :periodStart")
    Optional<WeeklyRankingPeriod> findForUpdate(@Param("periodStart") LocalDate periodStart);
}
