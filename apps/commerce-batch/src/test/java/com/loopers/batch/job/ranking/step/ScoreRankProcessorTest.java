package com.loopers.batch.job.ranking.step;

import com.loopers.batch.job.ranking.ProductRank;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ScoreRankProcessorTest {

    @DisplayName("몇 번째로 읽힌 점수인지가 그 상품의 계산 순위다.")
    @Test
    void ranksByReadPosition() {
        FrozenScore score = new FrozenScore(8, 1);
        score.setItemCount(3);

        assertThat(new ScoreRankProcessor().process(score)).isEqualTo(new ProductRank(8, 3));
    }
}
