package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.vo.RankingCompletionEvidence;
import com.loopers.domain.ranking.model.vo.RankingResultFingerprint;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RankingCompletionEvidenceTest {
    @Test
    @DisplayName("상품 집합·각 점수가 일치한 완성 근거는 같은 fingerprint를 승인하고 빈 완성본도 허용한다")
    void acceptsCompleteAndEmptyResults() {
        var result = new RankingResultFingerprint(3, "745d50bbd2f04a2e8c937f9ebf5f2cddbf75b958540e964934a424903b0a67d2");
        assertThat(new RankingCompletionEvidence(3, 0, result).requireComplete()).isSameAs(result);
        var empty = new RankingResultFingerprint(0, "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        assertThat(new RankingCompletionEvidence(0, 0, empty).requireComplete()).isSameAs(empty);
    }

    @ParameterizedTest
    @CsvSource({"3,1,3", "3,0,2", "3,0,4", "0,0,1", "-1,0,0", "3,-1,3"})
    @DisplayName("불일치 상품·누락·추가·음수 검증 근거는 완성을 거절한다")
    void rejectsIncompleteEvidence(long expected, long mismatched, long actualCount) {
        var result = new RankingResultFingerprint(actualCount, "a".repeat(64));
        var evidence = new RankingCompletionEvidence(expected, mismatched, result);
        assertThatThrownBy(evidence::requireComplete).isInstanceOf(SnapshotIntegrityException.class);
        assertThat(evidence.actual()).isSameAs(result);
    }

    @Test
    @DisplayName("실제 결과가 없는 근거는 완성을 승인하지 않는다")
    void rejectsAbsentResult() {
        assertThatThrownBy(() -> new RankingCompletionEvidence(0, 0, null).requireComplete())
            .isInstanceOf(SnapshotIntegrityException.class);
    }
}
