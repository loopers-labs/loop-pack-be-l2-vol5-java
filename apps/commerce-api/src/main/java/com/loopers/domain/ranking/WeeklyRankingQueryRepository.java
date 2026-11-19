package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WeeklyRankingQueryRepository {

    /** pointer 행이 없을 때만 empty. 연결이 깨진 pointer 도 반환해 호출자가 판정한다. */
    Optional<WeeklyRankingQueryResult.Publication> findPublishedSnapshot(LocalDate periodStart);

    /** 현재 Product·Brand 가 모두 미삭제인 항목만 저장 rank 순으로 limit 개. */
    List<WeeklyRankingQueryResult.Item> findVisibleItems(String snapshotId, int limit);
}
