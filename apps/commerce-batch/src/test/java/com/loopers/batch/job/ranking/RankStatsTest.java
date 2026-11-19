package com.loopers.batch.job.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RankStatsTest {

    @DisplayName("모든 행에 순위가 있고 1부터 행 수까지면 빠짐없이 매겨진 것이다. 행이 없어도 그렇다.")
    @Test
    void completeWhenRanksCoverOneToRows() {
        assertThat(new RankStats(3, 3, 1, 3).isComplete()).isTrue();
        assertThat(new RankStats(0, 0, null, null).isComplete()).isTrue();
    }

    @DisplayName("순위가 빈 행이 있거나, 1부터 시작하지 않거나, 중간이 끊기면 빠진 것이다.")
    @Test
    void incompleteOtherwise() {
        assertThat(new RankStats(3, 2, 1, 2).isComplete()).isFalse();
        assertThat(new RankStats(3, 3, 2, 4).isComplete()).isFalse();
        assertThat(new RankStats(2, 2, 1, 3).isComplete()).isFalse();
    }
}
