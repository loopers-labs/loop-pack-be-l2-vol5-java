package com.loopers.application.ranking;

import com.loopers.domain.ranking.WeeklyRankingQuery;
import com.loopers.domain.ranking.WeeklyRankingQueryRepository;
import com.loopers.domain.ranking.WeeklyRankingQueryResult.Publication;
import com.loopers.domain.ranking.WeeklyRankingQueryResult.SnapshotSummary;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** 공개 snapshot 확인과 항목 조회를 한 읽기 전용 TX 에서 같은 snapshotId 로 수행한다(랭킹 설계 7.6). */
@Component
@RequiredArgsConstructor
public class RankingFacade {

    private final WeeklyRankingQueryRepository repository;

    @Transactional(readOnly = true)
    public WeeklyRankingInfo getWeeklyRanking(WeeklyRankingQuery query) {
        Optional<Publication> publication = repository.findPublishedSnapshot(query.periodStart());
        if (publication.isEmpty()) {
            return new WeeklyRankingInfo(WeeklyRankingInfo.Status.NOT_PUBLISHED,
                query.periodStart(), query.periodEnd(), null, null, List.of());
        }
        SnapshotSummary snapshot = requireCompleted(publication.get(), query);
        return new WeeklyRankingInfo(WeeklyRankingInfo.Status.PUBLISHED, snapshot.periodStart(), snapshot.periodEnd(),
            snapshot.snapshotId(), snapshot.inputFixedAt(),
            List.copyOf(repository.findVisibleItems(snapshot.snapshotId(), query.limit())));
    }

    /** 깨진 공개 상태를 미공개로 숨기지 않고 INTERNAL_ERROR 로 실패시킨다. */
    private static SnapshotSummary requireCompleted(Publication publication, WeeklyRankingQuery query) {
        SnapshotSummary snapshot = publication.snapshot();
        boolean valid = !publication.deleted() && snapshot != null && !snapshot.deleted()
            && snapshot.snapshotId().equals(publication.publishedSnapshotId())
            && query.periodStart().equals(snapshot.periodStart()) && query.periodEnd().equals(snapshot.periodEnd())
            && "COMPLETED".equals(snapshot.status())
            && snapshot.inputFixedAt() != null && snapshot.completedAt() != null && snapshot.resultChecksum() != null;
        if (!valid) {
            throw new CoreException(ErrorType.INTERNAL_ERROR);
        }
        return snapshot;
    }
}
