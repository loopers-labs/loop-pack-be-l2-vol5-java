package com.loopers.application.ranking.command;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import java.util.Objects;
import java.util.UUID;

public record WeeklyRankingOrderPartitionCommand(UUID snapshotId, int orderPartitionId, RankingPeriod period, int revision,
                                       String ownerId, long generation) {
    public WeeklyRankingOrderPartitionCommand {
        Objects.requireNonNull(snapshotId);
        Objects.requireNonNull(period);
        if (orderPartitionId < 0 || revision <= 0 || generation <= 0 || ownerId == null || ownerId.isBlank() || ownerId.length() > 128) {
            throw new IllegalArgumentException("주문 파티션의 식별값, 주차 또는 소유권이 유효하지 않습니다.");
        }
    }
}
