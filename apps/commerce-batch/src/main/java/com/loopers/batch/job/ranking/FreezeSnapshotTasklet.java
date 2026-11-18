package com.loopers.batch.job.ranking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;

import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.jdbc.core.JdbcTemplate;

class FreezeSnapshotTasklet implements Tasklet {

    private static final DateTimeFormatter CONFIRMED_AT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

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
        List<WeeklySnapshot> existing = jdbc.query(
            "select period_start, revision from ranking_snapshot where snapshot_id = ?",
            (rs, rowNum) -> new WeeklySnapshot(rs.getObject("period_start", LocalDate.class), rs.getInt("revision")),
            snapshot.id()
        );
        if (!existing.isEmpty()) {
            if (!existing.get(0).equals(snapshot)) {
                throw new IllegalStateException(
                    "snapshot " + snapshot.id() + " already exists for " + existing.get(0)
                );
            }
            return RepeatStatus.FINISHED;
        }
        Integer latestRevision = jdbc.queryForObject(
            "select max(revision) from ranking_snapshot where period_start = ?",
            Integer.class, snapshot.periodStart()
        );
        if (latestRevision != null && latestRevision >= snapshot.revision()) {
            throw new IllegalStateException(
                "revision " + snapshot.revision() + " is not above latest revision " + latestRevision
                    + " of " + snapshot.periodStart()
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
        return new SortedRowsChecksum(jdbc).of("""
            select order_id, product_id, quantity, confirmed_at
            from ranking_snapshot_input
            where snapshot_id = ?
            order by order_id, product_id
            """, snapshotId,
            rs -> rs.getLong("order_id")
                + "\t" + rs.getLong("product_id")
                + "\t" + rs.getInt("quantity")
                + "\t" + rs.getObject("confirmed_at", LocalDateTime.class).format(CONFIRMED_AT)
        );
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
