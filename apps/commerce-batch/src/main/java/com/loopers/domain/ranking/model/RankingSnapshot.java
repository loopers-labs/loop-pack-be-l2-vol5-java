package com.loopers.domain.ranking.model;

import java.time.Instant;
import java.util.UUID;

public record RankingSnapshot(UUID snapshotId, RankingPeriod period, int revision, int inputContractVersion,
                              State state, Instant frozenAt, InputFingerprint inputs) {
    public enum State { FREEZING, FROZEN }

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
