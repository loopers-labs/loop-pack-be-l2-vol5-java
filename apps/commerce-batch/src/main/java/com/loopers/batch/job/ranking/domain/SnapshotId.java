package com.loopers.batch.job.ranking.domain;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;

public record SnapshotId(String value, RankingPeriod period, int revision) {
    private static final Pattern FORMAT = Pattern.compile("weekly-(\\d{8})-r([1-9]\\d{0,8})");

    public static SnapshotId of(RankingPeriod period, int revision) {
        if (revision < 1) {
            throw new IllegalArgumentException("revision must be positive: " + revision);
        }
        String value = "weekly-" + period.start().format(DateTimeFormatter.BASIC_ISO_DATE) + "-r" + revision;
        return new SnapshotId(value, period, revision);
    }

    public static SnapshotId parse(String value, RankingPeriod expectedPeriod) {
        if (value == null) {
            throw new IllegalArgumentException("snapshotId is required");
        }
        var matcher = FORMAT.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("snapshotId must be weekly-yyyyMMdd-rN: " + value);
        }
        LocalDate date;
        try {
            date = LocalDate.parse(matcher.group(1), DateTimeFormatter.BASIC_ISO_DATE);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("snapshotId has an invalid date: " + value, e);
        }
        if (!date.equals(expectedPeriod.start())) {
            throw new IllegalArgumentException(
                    "snapshotId date " + date + " does not match periodStart " + expectedPeriod.start());
        }
        return new SnapshotId(value, expectedPeriod, Integer.parseInt(matcher.group(2)));
    }

    @Override
    public String toString() {
        return value;
    }
}
