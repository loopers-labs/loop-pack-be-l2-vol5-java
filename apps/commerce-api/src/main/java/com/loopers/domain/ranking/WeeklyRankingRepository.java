package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** commerce-batch가 공개한 주간 랭킹(ranking_publication → weekly_ranking)을 읽는다. 쓰지 않는다. */
public interface WeeklyRankingRepository {

    /** 기간의 공개 pointer가 가리키는 완성 snapshot. 아직 공개된 적 없으면 비어 있다. */
    Optional<PublishedWeeklySnapshot> findPublished(LocalDate periodStart);

    /** 그 snapshot의 계산 순위 중 현재 고객에게 보이는 상품만 순위 순서로 limit개. */
    List<WeeklyRankingEntry> findVisibleEntries(String snapshotId, int limit);
}
