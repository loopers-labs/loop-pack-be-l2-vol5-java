package com.loopers.domain.ranking.model.vo;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import java.time.Instant;
import java.util.UUID;

public record CompletedWeeklyRanking(UUID snapshotId, int resultContractVersion,
                                     RankingResultFingerprint result, Instant completedAt) {
    public void requireResult(RankingResultFingerprint actual) {
        if (resultContractVersion != RankingResultFingerprint.CONTRACT_VERSION || completedAt == null || result == null
            || result.count() < 0 || result.sha256() == null || !result.sha256().matches("[0-9a-f]{64}")
            || !result.equals(actual)) {
            throw new SnapshotIntegrityException("저장된 완성본의 계약·건수·checksum이 실제 결과와 다릅니다: " + snapshotId);
        }
    }
}
