package com.loopers.domain.ranking;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductScoreTest {
    @Test
    void acceptsScoresBeyondIntegerRangeWithoutTruncation() {
        var score = new ProductScore("weekly-20260817-r1", 7, (long) Integer.MAX_VALUE + 1);
        assertThat(score.score()).isEqualTo(2147483648L);
        assertThat(new ProductScore(score.snapshotId(), score.productId(), Long.MAX_VALUE).score()).isEqualTo(Long.MAX_VALUE);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t"})
    void rejectsMissingSnapshotIdentity(String snapshotId) {
        assertThatThrownBy(() -> new ProductScore(snapshotId, 7, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsIdentityThatCannotFitTheStorageContract() {
        assertThatThrownBy(() -> new ProductScore("x".repeat(65), 7, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveProduct(long productId) {
        assertThatThrownBy(() -> new ProductScore("weekly-20260817-r1", productId, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1, Long.MIN_VALUE})
    void rejectsNonPositiveScore(long score) {
        assertThatThrownBy(() -> new ProductScore("weekly-20260817-r1", 7, score)).isInstanceOf(IllegalArgumentException.class);
    }
}
