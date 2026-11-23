package com.loopers.batch.job.ranking;

import java.time.DateTimeException;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.ResolverStyle;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * UTC 월요일 00:00부터 다음 월요일 00:00 전까지의 한 주. 시작은 포함하고 끝은 제외한다.
 * JVM 기본 시간대에 기대지 않고 UTC로만 계산한다.
 */
public record RankingPeriod(LocalDate start) {

    // 같은 날을 다른 모양으로 받으면 다른 작업이 되므로 yyyy-MM-dd 한 가지만 받는다.
    private static final Pattern SHAPE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final DateTimeFormatter FORMAT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd").withResolverStyle(ResolverStyle.STRICT);

    public RankingPeriod {
        Objects.requireNonNull(start, "start");
        if (start.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new IllegalArgumentException("기간은 월요일에 시작해야 한다. start=" + start);
        }
    }

    public static RankingPeriod parse(String value) {
        if (value == null || !SHAPE.matcher(value).matches()) {
            throw new IllegalArgumentException("기간 시작은 yyyy-MM-dd 모양이어야 한다. value=" + value);
        }
        try {
            return new RankingPeriod(LocalDate.parse(value, FORMAT));
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("없는 날짜다. value=" + value, e);
        }
    }

    public LocalDate end() {
        return start.plusDays(7);
    }

    /**
     * JDBC에 넣는 기간 시작. DB의 시각 컬럼은 UTC 날짜·시각이므로 UTC 자정의 LocalDateTime이다.
     */
    public LocalDateTime startAtUtc() {
        return start.atStartOfDay();
    }

    public LocalDateTime endAtUtc() {
        return end().atStartOfDay();
    }
}
