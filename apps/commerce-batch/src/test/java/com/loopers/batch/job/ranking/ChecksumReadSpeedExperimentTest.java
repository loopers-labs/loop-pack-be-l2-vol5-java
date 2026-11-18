package com.loopers.batch.job.ranking;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

@Tag("speed-experiment")
@SpringBootTest(properties = "spring.batch.job.enabled=false")
class ChecksumReadSpeedExperimentTest {

    private static final int[] SIZES = {100_000, 400_000};
    private static final int WARMUPS = 3;
    private static final int MEASUREMENTS = 10;
    private static final int BUFFERED = 0;
    private static final int ROW_BY_ROW = Integer.MIN_VALUE;
    private static final String SNAPSHOT_ID = "speed-1";
    private static final DateTimeFormatter CONFIRMED_AT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    @Autowired
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        jdbc.execute("truncate table ranking_snapshot_input");
    }

    @DisplayName("EXP-031: 기본 GC 에서 checksum 을 행 단위로 읽어도 결과 전체를 받는 것보다 20% 넘게 느리지 않다.")
    @Test
    void rowByRowIsNotSlower() {
        jdbc = new JdbcTemplate(dataSource);
        Map<Integer, double[]> ratios = new LinkedHashMap<>();
        for (int rows : SIZES) {
            seed(rows);
            assertThat(checksum(ROW_BY_ROW)).isEqualTo(checksum(BUFFERED));
            for (int i = 0; i < WARMUPS; i++) {
                checksum(BUFFERED);
                checksum(ROW_BY_ROW);
            }
            List<Long> buffered = new ArrayList<>();
            List<Long> rowByRow = new ArrayList<>();
            for (int i = 0; i < MEASUREMENTS; i++) {
                if (i % 2 == 0) {
                    buffered.add(timeMs(BUFFERED));
                    rowByRow.add(timeMs(ROW_BY_ROW));
                } else {
                    rowByRow.add(timeMs(ROW_BY_ROW));
                    buffered.add(timeMs(BUFFERED));
                }
            }
            long bufferedMedian = median(buffered);
            long rowByRowMedian = median(rowByRow);
            System.out.printf("EXP-031 rows=%d buffered=%s medianMs=%d%n", rows, sorted(buffered), bufferedMedian);
            System.out.printf("EXP-031 rows=%d rowByRow=%s medianMs=%d%n", rows, sorted(rowByRow), rowByRowMedian);
            ratios.put(rows, new double[] {(double) rowByRowMedian / bufferedMedian});
        }
        ratios.forEach((rows, ratio) -> System.out.printf("EXP-031 rows=%d ratio=%.2f%n", rows, ratio[0]));

        ratios.values().forEach(ratio -> assertThat(ratio[0]).isLessThanOrEqualTo(1.2));
    }

    private long timeMs(int fetchSize) {
        long startedAt = System.nanoTime();
        checksum(fetchSize);
        return (System.nanoTime() - startedAt) / 1_000_000;
    }

    private String checksum(int fetchSize) {
        MessageDigest digest = sha256();
        jdbc.query(
            connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                    select order_id, product_id, quantity, confirmed_at
                    from ranking_snapshot_input
                    where snapshot_id = ?
                    order by order_id, product_id
                    """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                statement.setFetchSize(fetchSize);
                statement.setString(1, SNAPSHOT_ID);
                return statement;
            },
            (RowCallbackHandler) rs -> {
                String line = rs.getLong("order_id")
                    + "\t" + rs.getLong("product_id")
                    + "\t" + rs.getInt("quantity")
                    + "\t" + rs.getObject("confirmed_at", LocalDateTime.class).format(CONFIRMED_AT)
                    + "\n";
                digest.update(line.getBytes(StandardCharsets.UTF_8));
            }
        );
        return HexFormat.of().formatHex(digest.digest());
    }

    private void seed(int rows) {
        jdbc.execute("truncate table ranking_snapshot_input");
        jdbc.execute((ConnectionCallback<Integer>) con -> {
            try (var session = con.createStatement()) {
                session.execute("set session cte_max_recursion_depth = " + rows);
            }
            try (var seed = con.prepareStatement("""
                insert into ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at)
                with recursive seq (n) as (
                  select 1 union all select n + 1 from seq where n < ?
                )
                select ?, n % 1000 + 1, n, 1, timestamp('2026-08-17') + interval (n % 604800) second
                from seq
                """)) {
                seed.setInt(1, rows);
                seed.setString(2, SNAPSHOT_ID);
                return seed.executeUpdate();
            }
        });
    }

    private static long median(List<Long> values) {
        return sorted(values).get(values.size() / 2);
    }

    private static List<Long> sorted(List<Long> values) {
        return values.stream().sorted().toList();
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
