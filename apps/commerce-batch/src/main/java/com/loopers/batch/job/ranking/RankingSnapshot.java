package com.loopers.batch.job.ranking;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** ranking_snapshot 한 행. 고정한 입력의 기간·revision·건수·checksum과 현재 상태를 담는다. */
public record RankingSnapshot(
    String snapshotId,
    LocalDate periodStart,
    int revision,
    Status status,
    LocalDateTime frozenAt,
    int inputCount,
    int productCount,
    String inputChecksum,
    String resultChecksum
) {
    public enum Status {
        /** 입력 고정이 commit됐다. 점수 계산·공개 대상이다. */
        FROZEN,
        /** 완성 ranking이 commit됐다. 공개 pointer 대상이다. */
        COMPLETED
    }

    public boolean sameWorkAs(WeeklySnapshotKey key) {
        return periodStart.equals(key.periodStart()) && revision == key.revision();
    }
}
