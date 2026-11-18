package com.loopers.batch.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * snapshot ID — 주(periodStart)와 revision으로 만든다 (W9 D-1). 예: 2026-08-17 주 r1 → "weekly-20260817-r1".
 */
public record SnapshotId(String value) {

    public static SnapshotId of(LocalDate periodStart, long revision) {
        return new SnapshotId("weekly-" + periodStart.format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision);
    }
}
