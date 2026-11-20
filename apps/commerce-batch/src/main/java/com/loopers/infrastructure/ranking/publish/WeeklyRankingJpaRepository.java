package com.loopers.infrastructure.ranking.publish;

import com.loopers.domain.ranking.publish.WeeklyRanking;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WeeklyRankingJpaRepository extends JpaRepository<WeeklyRanking, Long> {
}
