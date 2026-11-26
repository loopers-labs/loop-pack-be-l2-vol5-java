package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface WeeklyRankingRepository {

    /** 기간의 공개 pointer 가 가리키는 snapshot. 공개본이 없으면 비어 있다. */
    Optional<WeeklyRankingSnapshot> findPublishedSnapshot(LocalDate periodStart);

    /** 삭제되지 않은 상품만 저장된 순위 순서로 limit 개까지 읽는다. 순위 번호는 다시 매기지 않는다. */
    List<RankedProduct> findVisible(String snapshotId, int limit);
}
