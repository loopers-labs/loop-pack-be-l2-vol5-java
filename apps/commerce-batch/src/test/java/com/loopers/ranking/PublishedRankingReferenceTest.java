package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.vo.PublishedRankingReference;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PublishedRankingReferenceTest {
    @ParameterizedTest
    @ValueSource(ints = {1, Integer.MAX_VALUE})
    @DisplayName("공개 참조는 snapshot과 양수 revision의 최소·최댓값을 보존한다")
    void acceptsValidReference(int revision) {
        var snapshotId = UUID.randomUUID();
        var reference = new PublishedRankingReference(snapshotId, revision);
        assertThat(reference.snapshotId()).isEqualTo(snapshotId);
        assertThat(reference.revision()).isEqualTo(revision);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1, Integer.MIN_VALUE})
    @DisplayName("공개 참조의 revision은 0이나 음수일 수 없다")
    void rejectsInvalidRevision(int revision) {
        assertThatThrownBy(() -> new PublishedRankingReference(UUID.randomUUID(), revision))
            .isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    @DisplayName("공개 참조의 snapshot 식별자는 필수다")
    void rejectsMissingSnapshot() {
        assertThatThrownBy(() -> new PublishedRankingReference(null, 1)).isInstanceOf(SnapshotIntegrityException.class);
    }
}
