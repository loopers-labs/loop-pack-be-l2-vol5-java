package com.loopers.application.ranking.query;

import com.loopers.domain.ranking.RankingPeriod;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 조회 유스케이스 (설계 10-6). 공개 pointer 가 가리키는 snapshot 의 완성 ranking 만 돌려준다.
 * 계산 중 stage·다른 주·다른 revision 의 행을 섞지 않는다: pointer 로 snapshotId 를 정한 뒤 그 snapshotId 의 행만 읽고,
 * 완성 ranking 행은 공개 뒤 바뀌지 않는다. 두 조회는 한 읽기 TX 다.
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingReader {
    public static final int DEFAULT_LIMIT = 10;
    public static final int MAX_LIMIT = 100;

    private final WeeklyRankingQueryRepository weeklyRankingQueryRepository;

    @Transactional(readOnly = true)
    public WeeklyRankingView.Weekly getWeekly(String periodStart, String limit) {
        RankingPeriod period = RankingPeriod.parse(periodStart);
        int size = limitOf(limit);
        Optional<WeeklyRankingView.Publication> publication = weeklyRankingQueryRepository.findPublication(period.start());
        if (publication.isEmpty()) {
            return new WeeklyRankingView.Weekly(period.start(), period.end(), null, List.of());
        }
        List<WeeklyRankingView.Entry> items = weeklyRankingQueryRepository.findVisibleEntries(publication.get().snapshotId(), size);
        return new WeeklyRankingView.Weekly(period.start(), period.end(), publication.get(), items);
    }

    /** 없으면 10. 1 ~ 100 의 10진 정수만. */
    static int limitOf(String limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        int parsed;
        try {
            parsed = Integer.parseInt(limit);
        } catch (NumberFormatException e) {
            throw new CoreException(ErrorType.INVALID_RANKING_LIMIT, "limit 은 정수여야 합니다. [limit = " + limit + "]");
        }
        if (parsed < 1 || parsed > MAX_LIMIT) {
            throw new CoreException(ErrorType.INVALID_RANKING_LIMIT, "limit 은 1 이상 " + MAX_LIMIT + " 이하여야 합니다. [limit = " + limit + "]");
        }
        return parsed;
    }
}
