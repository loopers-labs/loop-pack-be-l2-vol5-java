package com.loopers.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.loopers.domain.ranking.model.vo.CompletedWeeklyRanking;
import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.vo.RankingResultFingerprint;
import com.loopers.domain.ranking.model.RankingSnapshot;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class RankingSnapshotIdentityTest {
    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final RankingPeriod PERIOD = new RankingPeriod(LocalDate.of(2026, 8, 17));
    private static final Instant FROZEN_AT = Instant.parse("2026-08-24T00:01:00Z");
    private static final InputFingerprint INPUTS = new InputFingerprint(4, "a".repeat(64));

    @Test
    @DisplayName("같은 snapshotId는 상태 내용이 달라도 같은 엔티티이며 hash 집합에서도 하나다")
    void identifiesSnapshotById() {
        var frozen = frozen(ID);
        var freezing = new RankingSnapshot(ID, PERIOD, 1, 1, RankingSnapshot.State.FREEZING, null, null);
        assertThat(frozen).isEqualTo(freezing);
        assertThat(frozen.hashCode()).isEqualTo(freezing.hashCode());
        assertThat(new HashSet<>(List.of(frozen, freezing))).hasSize(1);
        assertThat(frozen.state()).isEqualTo(RankingSnapshot.State.FROZEN);
        assertThat(frozen.frozenAt()).isEqualTo(FROZEN_AT);
        assertThat(frozen.inputs()).isEqualTo(INPUTS);
        assertThat(freezing.state()).isEqualTo(RankingSnapshot.State.FREEZING);
        assertThat(freezing.inputs()).isNull();
    }

    @Test
    @DisplayName("내용이 같아도 다른 snapshotId는 다른 엔티티다")
    void distinguishesDifferentIds() {
        assertThat(frozen(ID)).isNotEqualTo(frozen(UUID.fromString("00000000-0000-0000-0000-000000000002")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"period", "revision", "version"})
    @DisplayName("ID 기반 동등성이 같아도 다른 기간·revision·입력 계약을 거절한다")
    void checksContractIndependentlyOfIdentity(String mismatch) {
        var period = mismatch.equals("period") ? new RankingPeriod(PERIOD.start().plusWeeks(1)) : PERIOD;
        var candidate = new RankingSnapshot(ID, period, mismatch.equals("revision") ? 2 : 1,
            mismatch.equals("version") ? 2 : 1, RankingSnapshot.State.FROZEN, FROZEN_AT, INPUTS);
        assertThat(candidate).isEqualTo(frozen(ID));
        assertThatThrownBy(() -> candidate.requireContract(ID, PERIOD, 1, 1)).isInstanceOf(SnapshotConflictException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"state", "inputs"})
    @DisplayName("같은 ID의 고정 상태·입력 내용 검증은 생략하지 않는다")
    void checksContentsIndependentlyOfIdentity(String mismatch) {
        var candidate = mismatch.equals("state")
            ? new RankingSnapshot(ID, PERIOD, 1, 1, RankingSnapshot.State.FREEZING, null, null)
            : new RankingSnapshot(ID, PERIOD, 1, 1, RankingSnapshot.State.FROZEN, FROZEN_AT, new InputFingerprint(3, "b".repeat(64)));
        assertThat(candidate).isEqualTo(frozen(ID));
        assertThatThrownBy(() -> candidate.requireFrozen(INPUTS)).isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    @DisplayName("엔티티의 snapshot 식별자는 필수다")
    void rejectsMissingIdentity() {
        assertThatThrownBy(() -> frozen(null)).isInstanceOf(SnapshotIntegrityException.class);
    }

    @Test
    @DisplayName("완성본 VO는 전체 값으로 비교하고 재사용 시 결과 무결성도 확인한다")
    void preservesCompletionValueSemantics() {
        var result = new RankingResultFingerprint(3, "a".repeat(64));
        var completed = new CompletedWeeklyRanking(ID, 1, result, FROZEN_AT);
        assertThat(completed).isEqualTo(new CompletedWeeklyRanking(ID, 1, result, FROZEN_AT));
        assertThat(completed).isNotEqualTo(new CompletedWeeklyRanking(ID, 1, result, FROZEN_AT.plusSeconds(1)));
        completed.requireResult(result);
        assertThatThrownBy(() -> completed.requireResult(new RankingResultFingerprint(3, "b".repeat(64))))
            .isInstanceOf(SnapshotIntegrityException.class);
    }

    private RankingSnapshot frozen(UUID id) {
        return new RankingSnapshot(id, PERIOD, 1, 1, RankingSnapshot.State.FROZEN, FROZEN_AT, INPUTS);
    }
}
