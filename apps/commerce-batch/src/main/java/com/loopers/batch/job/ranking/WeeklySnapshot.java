package com.loopers.batch.job.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

public record WeeklySnapshot (LocalDate periodStart, int revision){
    public WeeklySnapshot{
        if(periodStart.getDayOfWeek()!= DayOfWeek.MONDAY){
            throw new IllegalArgumentException("periodStart 는 월요일이어야 한다: " + periodStart);
        }
        if(revision<1){
            throw new IllegalArgumentException("revision 은 1 이상이어야 한다: " + revision);
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
                "snapshotId " + requested + " 가 기간과 revision 으로 만든 값 " + id() + " 와 다르다"
            );
        }
    }

    public void requireSameAs(WeeklySnapshot existing) {
        if (!equals(existing)) {
            throw new IllegalStateException("snapshot " + id() + " 가 이미 다른 기간이나 revision 으로 있다: " + existing);
        }
    }

    public void requireAbove(int latestRevision) {
        if (revision <= latestRevision) {
            throw new IllegalStateException(
                "revision " + revision + " 이 " + periodStart + " 의 가장 큰 revision " + latestRevision + " 보다 크지 않다"
            );
        }
    }
}
