package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.WeeklyRankingSnapshotInput;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface WeeklyRankingSnapshotInputJpaRepository extends JpaRepository<WeeklyRankingSnapshotInput, Long> {

    List<WeeklyRankingSnapshotInput> findBySnapshotIdOrderByOrderIdAscProductIdAsc(String snapshotId);

    @Query("select coalesce(sum(i.quantity), 0) from WeeklyRankingSnapshotInput i "
        + "where i.snapshotId = :snapshotId and i.productId = :productId")
    long sumQuantity(@Param("snapshotId") String snapshotId, @Param("productId") Long productId);
}
