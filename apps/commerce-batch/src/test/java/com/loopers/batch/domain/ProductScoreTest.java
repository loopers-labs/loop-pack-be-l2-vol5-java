package com.loopers.batch.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductScoreTest {

    @Test
    void 점수는_그_주_구매_수량_합이다() {
        // given: 상품 7 = 주문 42의 2개 + 주문 43의 3개
        ProductTotal total = new ProductTotal(7L, 5L);

        // when
        ProductScore score = ProductScore.from(total);

        // then
        assertThat(score).isEqualTo(new ProductScore(7L, 5L));
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void 수량_합이_0_이하이면_거절한다(long sum) {
        // given
        ProductTotal total = new ProductTotal(7L, sum);

        // when · then
        assertThatThrownBy(() -> ProductScore.from(total))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("0 이하");
    }
}
