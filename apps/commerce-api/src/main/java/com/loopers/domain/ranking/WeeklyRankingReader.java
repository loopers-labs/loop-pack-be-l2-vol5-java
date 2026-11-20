package com.loopers.domain.ranking;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 공개된 주간 랭킹을 읽는 포트 (설계 D14, R5). pointer → COMPLETED 회차 → 그 snapshotId 의 점수 행 경로로만 읽는다.
 * snapshotId 를 직접 받아 아무 회차나 읽는 경로는 두지 않는다.
 */
public interface WeeklyRankingReader {

    Optional<PublishedSnapshot> findPublished(LocalDate periodStart);

    /** ranking 오름차순. findPublished 가 돌려준 snapshotId 로만 호출한다. */
    List<RankedScore> findRankedScores(String snapshotId);
}
