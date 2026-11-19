package com.loopers.domain.ranking.policy;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;

public final class WeeklyRankingOrderPolicy {
    private WeeklyRankingOrderPolicy() {}

    public record Row(long productId, long score, long rank) {}

    /** previous is the previously accepted row; null starts an independent sequence. */
    public static void requireValid(Row current, Row previous) {
        if (current == null || current.productId() <= 0 || current.score() <= 0 || current.rank() <= 0
            || (previous == null && current.rank() != 1)
            || (previous != null && (previous.rank() == Long.MAX_VALUE || current.rank() != previous.rank() + 1
                || current.score() > previous.score()
                || (current.score() == previous.score() && current.productId() <= previous.productId())))) {
            throw new SnapshotIntegrityException("완성 순위의 양수·연속 순번·점수 내림차순·동점 상품 순서가 다릅니다.");
        }
    }
}
