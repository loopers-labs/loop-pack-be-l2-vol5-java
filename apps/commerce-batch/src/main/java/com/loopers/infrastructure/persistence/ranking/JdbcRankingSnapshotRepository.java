package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.InputFingerprint;
import com.loopers.domain.ranking.model.RankingPeriod;
import com.loopers.domain.ranking.model.RankingSnapshot;
import com.loopers.domain.ranking.repository.RankingSnapshotRepository;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.policy.FrozenPurchaseInputChecksum;
import com.loopers.domain.ranking.repository.SnapshotReservationConflictException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.springframework.stereotype.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcRankingSnapshotRepository implements RankingSnapshotRepository {
    private static final Pattern RESERVATION_KEY = Pattern.compile(
        "for key ['`](?:[^'`]+\\.)?(PRIMARY|uk_weekly_snapshot_period_revision)['`]", Pattern.CASE_INSENSITIVE);
    private final JdbcTemplate jdbc;

    public JdbcRankingSnapshotRepository(DataSource dataSource) {
        jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<RankingSnapshot> findById(UUID snapshotId) {
        return jdbc.query("""
            SELECT snapshot_id,period_start,period_end,revision,input_contract_version,state,
                   frozen_at,input_count,input_checksum
            FROM weekly_ranking_snapshots WHERE snapshot_id=?
            """, (result, row) -> map(result), snapshotId.toString()).stream().findFirst();
    }

    @Override
    public void reserve(UUID snapshotId, RankingPeriod period, int revision) {
        try {
            jdbc.update("""
                INSERT INTO weekly_ranking_snapshots
                  (snapshot_id,period_start,period_end,revision,state,input_contract_version)
                VALUES (?,?,?,?,'FREEZING',?)
                """, snapshotId.toString(), period.start(), period.end(), revision, FrozenPurchaseInputChecksum.CONTRACT_VERSION);
        } catch (DuplicateKeyException exception) {
            if (isReservationDuplicate(exception)) {
                throw new SnapshotReservationConflictException(exception);
            }
            throw exception;
        }
    }

    @Override
    public void copyInputs(UUID snapshotId, RankingPeriod period) {
        jdbc.update("""
            INSERT INTO weekly_ranking_snapshot_inputs (snapshot_id,order_id,product_id,quantity,confirmed_at)
            SELECT ?,order_id,product_id,quantity,confirmed_at
            FROM product_purchase_activities
            WHERE confirmed_at>=? AND confirmed_at<?
            ORDER BY order_id,product_id
            """, snapshotId.toString(), LocalDateTime.ofInstant(period.startInclusive(), ZoneOffset.UTC),
            LocalDateTime.ofInstant(period.endExclusive(), ZoneOffset.UTC));
    }

    @Override
    public InputFingerprint fingerprint(UUID snapshotId, RankingPeriod period) {
        var checksum = new FrozenPurchaseInputChecksum(period);
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                SELECT order_id,product_id,quantity,confirmed_at FROM weekly_ranking_snapshot_inputs
                WHERE snapshot_id=? ORDER BY order_id,product_id
                """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setString(1, snapshotId.toString());
            statement.setFetchSize(Integer.MIN_VALUE);
            return statement;
        }, (org.springframework.jdbc.core.RowCallbackHandler) result -> checksum.accept(result.getLong("order_id"),
            result.getLong("product_id"), result.getInt("quantity"),
            result.getObject("confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)));
        // JdbcTemplate has consumed and closed the streaming ResultSet before the next manifest statement.
        return checksum.finish();
    }

    @Override
    public RankingSnapshot complete(UUID snapshotId, InputFingerprint inputs) {
        int updated = jdbc.update("""
            UPDATE weekly_ranking_snapshots
            SET state='FROZEN',frozen_at=UTC_TIMESTAMP(6),input_count=?,input_checksum=?
            WHERE snapshot_id=? AND state='FREEZING' AND input_contract_version=?
            """, inputs.count(), inputs.sha256(), snapshotId.toString(), FrozenPurchaseInputChecksum.CONTRACT_VERSION);
        if (updated != 1) {
            throw new SnapshotIntegrityException("snapshot 고정 완료 전이가 적용되지 않았습니다: " + snapshotId);
        }
        return findById(snapshotId).orElseThrow(() -> new SnapshotIntegrityException("완료한 snapshot이 없습니다."));
    }

    private RankingSnapshot map(ResultSet result) throws SQLException {
        var period = new RankingPeriod(result.getObject("period_start", LocalDate.class));
        if (!period.end().equals(result.getObject("period_end", LocalDate.class))) {
            throw new SnapshotIntegrityException("저장된 snapshot의 기간 끝이 다릅니다.");
        }
        var frozenAt = result.getObject("frozen_at", LocalDateTime.class);
        var count = result.getObject("input_count", Long.class);
        String checksum = result.getString("input_checksum");
        return new RankingSnapshot(UUID.fromString(result.getString("snapshot_id")), period, result.getInt("revision"),
            result.getInt("input_contract_version"), RankingSnapshot.State.valueOf(result.getString("state")),
            frozenAt == null ? null : frozenAt.toInstant(ZoneOffset.UTC),
            count == null || checksum == null ? null : new InputFingerprint(count, checksum));
    }

    private boolean isReservationDuplicate(Throwable exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == 1062
                && RESERVATION_KEY.matcher(sql.getMessage()).find()) {
                return true;
            }
        }
        return false;
    }
}
