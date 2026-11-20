package com.loopers.domain.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RankingLimitTest {

    @DisplayName("QRY-003: 1 부터 100 까지 받는다.")
    @Test
    void acceptsOneToHundred() {
        assertAll(
            () -> assertThat(new RankingLimit(1).value()).isEqualTo(1),
            () -> assertThat(new RankingLimit(100).value()).isEqualTo(100)
        );
    }

    @DisplayName("QRY-003: 0 이하나 100 초과는 거부한다.")
    @Test
    void rejectsOutOfRange() {
        assertAll(
            () -> assertThatThrownBy(() -> new RankingLimit(0)).isInstanceOf(IllegalArgumentException.class),
            () -> assertThatThrownBy(() -> new RankingLimit(101)).isInstanceOf(IllegalArgumentException.class)
        );
    }

    @DisplayName("QRY-003: 생략하면 10 이다.")
    @Test
    void defaultsToTen() {
        assertThat(RankingLimit.DEFAULT.value()).isEqualTo(10);
    }
}
