package com.loopers.domain.ranking;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RankingOrderTest {

    @DisplayName("점수 내림차순으로 순위를 매기고, 동점이면 productId 가 작은 상품이 앞선다.")
    @Test
    void ranksByScoreDescThenProductIdAsc() {
        // act
        List<RankedProduct> ranked = RankingOrder.rank(List.of(
            new ProductScore(8L, 1), new ProductScore(9L, 5), new ProductScore(7L, 5)
        ));

        // assert
        assertThat(ranked).containsExactly(
            new RankedProduct(7L, 5, 1),
            new RankedProduct(9L, 5, 2),
            new RankedProduct(8L, 1, 3)
        );
    }
}
