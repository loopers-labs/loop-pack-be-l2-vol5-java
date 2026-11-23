package com.loopers.domain.product.service;

import com.loopers.domain.product.entity.WeeklyRankingSnapshot;
import com.loopers.domain.product.enums.ProductErrorCode;
import com.loopers.domain.product.repository.WeeklyRankingRepository;
import com.loopers.domain.product.vo.WeeklyRankingView;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class WeeklyRankingService {

    public static final int MAX_LIMIT = 100;

    private final WeeklyRankingRepository weeklyRankingRepository;

    /**
     * 공개 snapshot 을 먼저 찾고, 그 snapshotId 로만 항목을 읽는다. 그사이 새 revision 이 공개돼도
     * 한 snapshot 의 점수 행은 바뀌지 않아 서로 다른 snapshot 이 섞이지 않는다 (9주차 D-189)
     */
    @Transactional(readOnly = true)
    public WeeklyRankingView getWeeklyRanking(LocalDate periodStart, int limit) {
        if (periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ProductErrorCode.INVALID_RANKING_PERIOD);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new CoreException(ProductErrorCode.INVALID_RANKING_LIMIT);
        }
        Optional<WeeklyRankingSnapshot> published = weeklyRankingRepository.findPublished(periodStart);
        if (published.isEmpty()) {
            return new WeeklyRankingView.NotPublished(periodStart);
        }
        WeeklyRankingSnapshot snapshot = published.get();
        return new WeeklyRankingView.Published(
            periodStart,
            snapshot.getSnapshotId(),
            snapshot.getFrozenAt(),
            weeklyRankingRepository.findVisibleRanks(snapshot.getSnapshotId(), limit)
        );
    }
}
