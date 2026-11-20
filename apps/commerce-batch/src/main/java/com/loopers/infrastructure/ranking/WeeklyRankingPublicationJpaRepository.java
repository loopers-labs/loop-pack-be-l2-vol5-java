package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingPublication;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface WeeklyRankingPublicationJpaRepository extends JpaRepository<WeeklyRankingPublication, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from WeeklyRankingPublication p where p.periodStart = :periodStart")
    Optional<WeeklyRankingPublication> findForUpdate(@Param("periodStart") LocalDate periodStart);
}
