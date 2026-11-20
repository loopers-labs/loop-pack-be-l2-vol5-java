package com.loopers.application.ranking.command;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import java.time.LocalDate;
import java.util.UUID;

public record PublishWeeklyRankingCommand(UUID snapshotId, LocalDate periodStart, int revision) {
    public PublishWeeklyRankingCommand {
        if (snapshotId == null || revision <= 0) {
            throw new IllegalArgumentException("snapshotId와 양수 revision이 필요합니다.");
        }
        new RankingPeriod(periodStart);
    }

    public RankingPeriod period() {
        return new RankingPeriod(periodStart);
    }
}
