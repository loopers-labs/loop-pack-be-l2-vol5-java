package com.loopers.ranking.application;

import com.loopers.ranking.application.port.in.WeeklyRankingInfo;
import com.loopers.ranking.application.port.out.RankingQueryPort;
import com.loopers.ranking.application.port.out.RankingQueryPort.PublishedSnapshot;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 조회 (W9 D-6). 공개 pointer가 가리키는 snapshot만 읽는다. 계산 중인 snapshot이나 다른 주를 대신 내주지 않는다.
 * pointer를 먼저 읽고 그 snapshotId의 행을 읽는다. 공개된 snapshot의 행은 바뀌지 않으므로, 그사이 pointer가 바뀌어도
 * 응답의 snapshotId와 행이 섞이지 않는다.
 */
@RequiredArgsConstructor
@Component
public class RankingQueryService {

    private final RankingQueryPort rankingQueryPort;

    @Transactional(readOnly = true)
    public WeeklyRankingInfo getWeeklyRanking(LocalDate periodStart, int limit) {
        Optional<PublishedSnapshot> published = rankingQueryPort.findPublished(periodStart);
        if (published.isEmpty()) {
            return WeeklyRankingInfo.notPublished(periodStart);
        }
        PublishedSnapshot snapshot = published.get();
        List<WeeklyRankingInfo.Item> items = rankingQueryPort.findRankedProducts(snapshot.snapshotId(), limit).stream()
            .map(row -> new WeeklyRankingInfo.Item(row.rank(), row.productId(), row.score(), row.name(), row.price(), row.brandId(), row.brandName()))
            .toList();
        return WeeklyRankingInfo.published(periodStart, snapshot.snapshotId(), snapshot.frozenAt(), items);
    }
}
