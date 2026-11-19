package com.loopers.domain.ranking;

import java.time.ZonedDateTime;
import java.util.List;

public interface WeeklyRankingSnapshotInputRepository {

    /** 기간에 확정된 구매 활동을 한 문장으로 snapshot 입력에 복사하고 복사한 행 수를 돌려준다. */
    int copyPurchases(String snapshotId, WeeklyPeriod period, ZonedDateTime copiedAt);

    List<SnapshotInputLine> findLines(String snapshotId);

    long sumQuantity(String snapshotId, Long productId);
}
