package com.loopers.batch.job.ranking;

/**
 * 한 회차의 순위가 얼마나 매겨졌는지. 같은 회차 안의 순위는 유일 제약이 겹치지 않게 막으므로,
 * 모든 행에 순위가 있고 최솟값이 1, 최댓값이 행 수면 순위는 1부터 행 수까지 빠짐없이 이어진다.
 */
public record RankStats(long rows, long ranked, Integer minRank, Integer maxRank) {

    public boolean isComplete() {
        if (rows != ranked) {
            return false;
        }
        return rows == 0 || (minRank == 1 && maxRank == rows);
    }
}
