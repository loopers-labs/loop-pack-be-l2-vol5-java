package com.loopers.application.ranking;

import com.loopers.domain.ranking.PublishedRevision;
import com.loopers.domain.ranking.RankingPeriod;
import com.loopers.domain.ranking.WeeklyRankingReader;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.Optional;

/**
 * 주간 랭킹을 두 번 나눠 읽는다. 먼저 가장 최근에 완성된 회차를 찾고, 있으면 그 회차의 순위만 읽는다.
 * 완성된 회차의 점수와 순위 행은 바뀌지도 지워지지도 않으므로, 두 조회 사이에 새 회차가 완성돼도 처음 찾은 회차를 끝까지 보여 준다.
 */
@Component
public class WeeklyRankingFacade {

    private static final int MIN_LIMIT = 1;
    private static final int MAX_LIMIT = 100;

    private final WeeklyRankingReader reader;

    public WeeklyRankingFacade(WeeklyRankingReader reader) {
        this.reader = reader;
    }

    public WeeklyRankingInfo getWeeklyRanking(LocalDate periodStart, int limit) {
        if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
            throw new CoreException(ErrorType.BAD_REQUEST, "limit은 1부터 100까지입니다.");
        }
        RankingPeriod period = RankingPeriod.of(periodStart);
        Optional<PublishedRevision> published = reader.findPublished(period.start());
        if (published.isEmpty()) {
            return WeeklyRankingInfo.notPublished(period);
        }
        return WeeklyRankingInfo.published(period, published.get(),
            reader.findRankedProducts(period.start(), published.get().revision(), limit));
    }
}
