package com.loopers.domain.ranking.model.vo;

import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import java.util.UUID;

public record PublishedRankingReference(UUID snapshotId, int revision) {
    public PublishedRankingReference {
        if (snapshotId == null || revision <= 0) {
            throw new SnapshotIntegrityException("공개 참조에는 snapshotId와 양수 revision이 필요합니다.");
        }
    }
}
