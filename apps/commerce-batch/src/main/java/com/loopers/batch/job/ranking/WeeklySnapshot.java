package com.loopers.batch.job.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public record WeeklySnapshot (LocalDate periodStart, int revision){
    public WeeklySnapshot{
        if(periodStart.getDayOfWeek()!= DayOfWeek.MONDAY){
            throw new IllegalArgumentException("periodStart는 Monday"+periodStart);
        }
        if(revision<1){
            throw new IllegalArgumentException("revision must be positive: "+revision);
        }
    }
    public String id() {
        return "weekly-%s-r%d".formatted(periodStart.format(DateTimeFormatter.BASIC_ISO_DATE), revision);
    }

    public Instant start() {
        return periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public Instant end() {
        return periodStart.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    public void requireId(String requested) {
        if (!id().equals(requested)) {
            throw new IllegalArgumentException(
                "snapshotId " + requested + " does not match period and revision: " + id()
            );
        }
    }

    public void requireSameAs(WeeklySnapshot existing) {
        if (!equals(existing)) {
            throw new IllegalStateException("snapshot " + id() + " already exists for " + existing);
        }
    }

    public void requireAbove(int latestRevision) {
        if (revision <= latestRevision) {
            throw new IllegalStateException(
                "revision " + revision + " is not above latest revision " + latestRevision + " of " + periodStart
            );
        }
    }
}
