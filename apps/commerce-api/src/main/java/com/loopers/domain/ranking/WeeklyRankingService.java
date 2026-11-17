package com.loopers.domain.ranking;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 주간 랭킹 조회 규칙 (docs/week9/design.md 7절).
 * periodStart는 UTC 주의 월요일이어야 하고, limit은 1~100이다. 공개 여부와 관계없이 먼저 검증한다.
 * 요청한 주의 공개본만 돌려주며, 다른 주나 계산 중인 결과로 대신하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class WeeklyRankingService {

    public static final int MAX_LIMIT = 100;

    private final WeeklyRankingRepository weeklyRankingRepository;

    public Optional<PublishedWeeklySnapshot> getPublished(LocalDate periodStart, int limit) {
        if (periodStart.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new CoreException(ErrorType.BAD_REQUEST, "periodStart는 월요일이어야 합니다. periodStart=" + periodStart);
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new CoreException(ErrorType.BAD_REQUEST, "limit은 1 이상 " + MAX_LIMIT + " 이하여야 합니다. limit=" + limit);
        }
        return weeklyRankingRepository.findPublished(periodStart);
    }

    /** 공개 pointer에서 읽은 snapshotId의 행만 읽는다. ranking 행은 공개 뒤 바뀌지 않으므로 도중에 pointer가 바뀌어도 다른 snapshot과 섞이지 않는다. */
    public List<WeeklyRankingEntry> getVisibleEntries(PublishedWeeklySnapshot snapshot, int limit) {
        return weeklyRankingRepository.findVisibleEntries(snapshot.snapshotId(), limit);
    }
}
