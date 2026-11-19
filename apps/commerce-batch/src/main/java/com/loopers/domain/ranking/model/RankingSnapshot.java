package com.loopers.domain.ranking.model;

import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import java.time.Instant;
import java.util.UUID;

public final class RankingSnapshot {
    public enum State { FREEZING, FROZEN }

    private final UUID snapshotId;
    private final RankingPeriod period;
    private final int revision;
    private final int inputContractVersion;
    private final State state;
    private final Instant frozenAt;
    private final InputFingerprint inputs;

    public RankingSnapshot(UUID snapshotId, RankingPeriod period, int revision, int inputContractVersion,
                           State state, Instant frozenAt, InputFingerprint inputs) {
        if (snapshotId == null) {
            throw new SnapshotIntegrityException("snapshot 엔티티의 식별자는 필수입니다.");
        }
        this.snapshotId = snapshotId;
        this.period = period;
        this.revision = revision;
        this.inputContractVersion = inputContractVersion;
        this.state = state;
        this.frozenAt = frozenAt;
        this.inputs = inputs;
    }

    public UUID snapshotId() {
        return snapshotId;
    }

    public RankingPeriod period() {
        return period;
    }

    public int revision() {
        return revision;
    }

    public int inputContractVersion() {
        return inputContractVersion;
    }

    public State state() {
        return state;
    }

    public Instant frozenAt() {
        return frozenAt;
    }

    public InputFingerprint inputs() {
        return inputs;
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof RankingSnapshot that && snapshotId.equals(that.snapshotId);
    }

    @Override
    public int hashCode() {
        return snapshotId.hashCode();
    }

    public void requireContract(UUID expectedId, RankingPeriod expectedPeriod, int expectedRevision, int expectedVersion) {
        if (!expectedId.equals(snapshotId) || !expectedPeriod.equals(period) || expectedRevision != revision
            || expectedVersion != inputContractVersion) {
            throw new SnapshotConflictException("동일 snapshotId의 기간·revision·입력 계약이 다릅니다: " + expectedId);
        }
    }

    public void requireFrozen(InputFingerprint actual) {
        if (state != State.FROZEN || frozenAt == null || inputs == null || inputs.count() < 0
            || inputs.sha256() == null || !inputs.sha256().matches("[0-9a-f]{64}") || !inputs.equals(actual)) {
            throw new SnapshotIntegrityException("고정 입력의 상태·건수·checksum이 manifest와 다릅니다: " + snapshotId);
        }
    }
}
