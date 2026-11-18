package com.loopers.application.ranking.query;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/** 주간 랭킹 조회 (TB-19·22·23 + 카탈로그 표시 정보). 쓰기는 commerce-batch 만 한다. */
public interface WeeklyRankingQueryRepository {

    /** 기간의 공개 pointer 와 그 snapshot 정보. 없으면 아직 공개 전. */
    Optional<WeeklyRankingView.Publication> findPublication(LocalDate periodStart);

    /** 그 snapshot 의 완성 ranking 중 고객 공개 상품만 저장된 순위 순으로, 공개 조건을 적용한 뒤 limit 개. */
    List<WeeklyRankingView.Entry> findVisibleEntries(String snapshotId, int limit);
}
