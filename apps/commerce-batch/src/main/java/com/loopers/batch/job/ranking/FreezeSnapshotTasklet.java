package com.loopers.batch.job.ranking;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

class FreezeSnapshotTasklet implements Tasklet {

    private static final DateTimeFormatter CONFIRMED_AT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private static final int ROW_BY_ROW = Integer.MIN_VALUE;

    private final JdbcTemplate jdbc;

    FreezeSnapshotTasklet(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobParameters params = chunkContext.getStepContext().getStepExecution().getJobParameters();
        WeeklySnapshot snapshot = new WeeklySnapshot(
            params.getLocalDate("periodStart"),
            Math.toIntExact(params.getLong("revision"))
        );
        String requestedId = params.getString("snapshotId");
        if (!snapshot.id().equals(requestedId)) {
            throw new IllegalArgumentException(
                "snapshotId " + requestedId + " does not match period and revision: " + snapshot.id()
            );
        }

        int inputCount = jdbc.update("""
            insert into ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at)
            select ?, product_id, order_id, quantity, confirmed_at
            from purchase_activity
            where confirmed_at >= ? and confirmed_at < ?
            """, snapshot.id(), utc(snapshot.start()), utc(snapshot.end()));

        jdbc.update("""
            insert into ranking_snapshot
              (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum)
            values (?, ?, ?, 'FROZEN', ?, ?, ?)
            """,
            snapshot.id(), snapshot.periodStart(), snapshot.revision(),
            LocalDateTime.now(ZoneOffset.UTC), inputCount, inputChecksum(snapshot.id())
        );
        return RepeatStatus.FINISHED;
    }

    private String inputChecksum(String snapshotId) {
        MessageDigest digest = sha256();
        jdbc.query(
            connection -> {
                PreparedStatement statement = connection.prepareStatement("""
                    select order_id, product_id, quantity, confirmed_at
                    from ranking_snapshot_input
                    where snapshot_id = ?
                    order by order_id, product_id
                    """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
                statement.setFetchSize(ROW_BY_ROW);
                statement.setString(1, snapshotId);
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

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
