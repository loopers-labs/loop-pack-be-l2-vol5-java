package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.infrastructure.persistence.ranking.RankingFingerprintAccumulator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WeeklyRankingChecksumTest {
    @Test
    @DisplayName("순위순 UTF-8 TAB LF 직렬화는 수작업 정답 checksum과 일치한다")
    void hashesGoldenRanking() {
        var checksum = new RankingFingerprintAccumulator();
        checksum.accept(7, 5, 1);
        checksum.accept(9, 5, 2);
        checksum.accept(8, 1, 3);
        assertThat(checksum.finish().count()).isEqualTo(3);
        assertThat(checksum.finish().sha256()).isEqualTo("745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
    }

    @Test
    @DisplayName("빈 완성본은 빈 바이트 SHA-256이며 finish 반복에도 같은 값이다")
    void hashesEmptyRanking() {
        var checksum = new RankingFingerprintAccumulator();
        assertThat(checksum.finish().count()).isZero();
        assertThat(checksum.finish().sha256()).isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(checksum.finish()).isEqualTo(checksum.finish());
    }

    @ParameterizedTest
    @CsvSource({"0,5,2", "9,0,2", "9,-1,2", "9,5,1", "9,5,3", "6,5,2", "9,6,2"})
    @DisplayName("양수 식별·점수·연속 순위·동점 productId 순서를 위반하면 거절하고 상태를 보존한다")
    void rejectsInvalidRow(long productId, long score, long rank) {
        var checksum = new RankingFingerprintAccumulator();
        checksum.accept(7, 5, 1);
        assertThatThrownBy(() -> checksum.accept(productId, score, rank)).isInstanceOf(SnapshotIntegrityException.class);
        checksum.accept(9, 5, 2);
        assertThat(checksum.finish().count()).isEqualTo(2);
    }

    @Test
    @DisplayName("완료한 checksum은 행 추가를 거절한다")
    void rejectsWriteAfterFinish() {
        var checksum = new RankingFingerprintAccumulator();
        checksum.finish();
        assertThatThrownBy(() -> checksum.accept(7, 5, 1)).isInstanceOf(IllegalStateException.class);
    }
}
