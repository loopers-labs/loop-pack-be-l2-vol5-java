package com.loopers.domain.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeeklyRankingScoreStageTest {

    @DisplayName("같은 상품의 점수를 다시 쓰면 더하지 않고 같은 값으로 덮는다.")
    @Test
    void overwritesInsteadOfAdding() {
        // arrange
        WeeklyRankingScoreStage stage = new WeeklyRankingScoreStage("weekly-20260817-r1", new ProductScore(7L, 5));

        // act
        stage.overwrite(new ProductScore(7L, 5));

        // assert
        assertThat(stage.getScore()).isEqualTo(5);
    }
}
