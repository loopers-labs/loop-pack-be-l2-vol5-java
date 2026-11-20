package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankedProduct;
import com.loopers.domain.ranking.WeeklyRanking;
import com.loopers.domain.ranking.WeeklyRankingSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WeeklyRankingJpaRepository extends JpaRepository<WeeklyRanking, Long> {

    @Query("select s from WeeklyRankingPublication p join WeeklyRankingSnapshot s on s.snapshotId = p.publishedSnapshotId "
        + "where p.periodStart = :periodStart")
    Optional<WeeklyRankingSnapshot> findPublishedSnapshot(@Param("periodStart") LocalDate periodStart);

    // 공개 조건(삭제되지 않은 상품)을 먼저 적용한 뒤 limit 을 적용한다.
    @Query("select new com.loopers.domain.ranking.RankedProduct(r, p) from WeeklyRanking r "
        + "join Product p on p.id = r.productId and p.deletedAt is null "
        + "where r.snapshotId = :snapshotId order by r.ranking")
    List<RankedProduct> findVisible(@Param("snapshotId") String snapshotId, Pageable pageable);
}
