package com.loopers.batch.job.reconcile;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;

// DB 시각은 UTC로 저장한다(jpa.yml hibernate.jdbc.time_zone=UTC와 같은 기준). JVM 시간대와 무관하게 LocalDateTime으로 주고받는다.
public class ReconcileCheckpointRepository {
    static final String NAME = "order-confirmed-republish";

    private final JdbcTemplate jdbcTemplate;

    public ReconcileCheckpointRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ReconcileCheckpoint> find() {
        List<ReconcileCheckpoint> found = jdbcTemplate.query(
            "select watermark_confirmed_at, watermark_order_id from reconcile_checkpoint where name = ?",
            (rs, rowNum) -> new ReconcileCheckpoint(
                rs.getObject("watermark_confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC),
                rs.getLong("watermark_order_id")),
            NAME);
        return found.stream().findFirst();
    }

    public void save(ReconcileCheckpoint checkpoint, Instant updatedAt) {
        jdbcTemplate.update("insert into reconcile_checkpoint(name, watermark_confirmed_at, watermark_order_id, updated_at)"
                + " values (?, ?, ?, ?) on duplicate key update watermark_confirmed_at = values(watermark_confirmed_at),"
                + " watermark_order_id = values(watermark_order_id), updated_at = values(updated_at)",
            NAME, utc(checkpoint.confirmedAt()), checkpoint.orderId(), utc(updatedAt));
    }

    static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
