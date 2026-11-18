package com.loopers.batch.job.ranking;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

record WeeklySnapshot (LocalDate periodStart, int revision){
    WeeklySnapshot{
        if(periodStart.getDayOfWeek()!= DayOfWeek.MONDAY){
            throw new IllegalArgumentException("periodStart는 Monday"+periodStart);
        }
        if(revision<1){
            throw new IllegalArgumentException("revision must be positive: "+revision);
        }
    }
    String id() {
        return "weekly-%s-r%d".formatted(periodStart.format(DateTimeFormatter.BASIC_ISO_DATE), revision);
    }

    Instant start() {
        return periodStart.atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    Instant end() {
        return periodStart.plusWeeks(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
