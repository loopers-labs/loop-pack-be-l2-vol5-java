package com.loopers.batch.job.ranking;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.OptionalInt;

import org.springframework.jdbc.core.JdbcTemplate;

public class SnapshotStore {

    private static final DateTimeFormatter CONFIRMED_AT =
        DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'");

    private final JdbcTemplate jdbc;

    public SnapshotStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<WeeklySnapshot> find(String snapshotId) {
        return jdbc.query(
            "select period_start, revision from ranking_snapshot where snapshot_id = ?",
            (rs, rowNum) -> new WeeklySnapshot(rs.getObject("period_start", LocalDate.class), rs.getInt("revision")),
            snapshotId
        ).stream().findFirst();
    }

    public OptionalInt latestRevision(LocalDate periodStart) {
        Integer latest = jdbc.queryForObject(
            "select max(revision) from ranking_snapshot where period_start = ?", Integer.class, periodStart
        );
        return latest == null ? OptionalInt.empty() : OptionalInt.of(latest);
    }

    public int copyInput(WeeklySnapshot snapshot) {
        return jdbc.update("""
            insert into ranking_snapshot_input (snapshot_id, product_id, order_id, quantity, confirmed_at)
            select ?, product_id, order_id, quantity, confirmed_at
            from purchase_activity
            where confirmed_at >= ? and confirmed_at < ?
            """, snapshot.id(), utc(snapshot.start()), utc(snapshot.end()));
    }

    public String inputChecksum(String snapshotId) {
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

    public void markFrozen(WeeklySnapshot snapshot, int inputCount, String inputChecksum) {
        jdbc.update("""
            insert into ranking_snapshot
              (snapshot_id, period_start, revision, status, frozen_at, input_count, input_checksum)
            values (?, ?, ?, 'FROZEN', ?, ?, ?)
            """,
            snapshot.id(), snapshot.periodStart(), snapshot.revision(),
            LocalDateTime.now(ZoneOffset.UTC), inputCount, inputChecksum
        );
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
