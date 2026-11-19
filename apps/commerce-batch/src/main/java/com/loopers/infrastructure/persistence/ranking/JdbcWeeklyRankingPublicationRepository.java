package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.vo.CompletedWeeklyRanking;
import com.loopers.domain.ranking.model.vo.PublishedRankingReference;
import com.loopers.domain.ranking.model.vo.RankingCompletionEvidence;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.domain.ranking.model.vo.RankingResultFingerprint;
import com.loopers.domain.ranking.model.RankingSnapshot;
import com.loopers.domain.ranking.model.SnapshotConflictException;
import com.loopers.domain.ranking.model.SnapshotIntegrityException;
import com.loopers.domain.ranking.repository.WeeklyRankingPublicationRepository;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@Transactional(propagation = Propagation.MANDATORY)
public class JdbcWeeklyRankingPublicationRepository implements WeeklyRankingPublicationRepository {
    private final JdbcTemplate jdbc;

    public JdbcWeeklyRankingPublicationRepository(DataSource source) {
        jdbc = new JdbcTemplate(source);
    }

    @Override
    public Optional<PublishedRankingReference> lockPointer(RankingPeriod period) {
        jdbc.update("""
            INSERT INTO weekly_ranking_publications (period_start) VALUES (?)
            ON DUPLICATE KEY UPDATE period_start=?
            """, period.start(), period.start());
        return jdbc.query("""
            SELECT snapshot_id,revision FROM weekly_ranking_publications WHERE period_start=? FOR UPDATE
            """, (row, index) -> row.getString("snapshot_id") == null ? Optional.<PublishedRankingReference>empty()
                : Optional.of(new PublishedRankingReference(UUID.fromString(row.getString("snapshot_id")),
                    row.getInt("revision"))),
            period.start()).stream().findFirst().orElseThrow(() -> new SnapshotIntegrityException("잠글 기간 pointer가 없습니다."));
    }

    @Override
    public Optional<CompletedWeeklyRanking> findCompletion(UUID snapshotId) {
        return jdbc.query("""
            SELECT result_contract_version,product_count,result_checksum,completed_at
            FROM weekly_ranking_completions WHERE snapshot_id=?
            """, (row, index) -> new CompletedWeeklyRanking(snapshotId, row.getInt("result_contract_version"),
                new RankingResultFingerprint(row.getLong("product_count"), row.getString("result_checksum")),
                row.getObject("completed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)), snapshotId.toString())
            .stream().findFirst();
    }

    @Override
    public void materialize(UUID snapshotId) {
        jdbc.update("""
            INSERT INTO weekly_rankings (snapshot_id,product_id,score,ranking_position)
            SELECT snapshot_id,product_id,score,ROW_NUMBER() OVER (ORDER BY score DESC,product_id ASC)
            FROM weekly_ranking_score_stage WHERE snapshot_id=?
            """, snapshotId.toString());
    }

    @Override
    public RankingCompletionEvidence inspectRankings(UUID snapshotId) {
        String id = snapshotId.toString();
        long expected = jdbc.queryForObject("""
            SELECT COUNT(DISTINCT product_id) FROM weekly_ranking_snapshot_inputs WHERE snapshot_id=?
            """, Long.class, id);
        long mismatches = jdbc.queryForObject("""
            SELECT COUNT(*) FROM (
                SELECT product_id,SUM(quantity) AS expected_score FROM weekly_ranking_snapshot_inputs
                WHERE snapshot_id=? GROUP BY product_id
            ) expected LEFT JOIN weekly_rankings actual ON actual.snapshot_id=? AND actual.product_id=expected.product_id
            WHERE actual.product_id IS NULL OR actual.score<>expected.expected_score
            """, Long.class, id, id);
        var checksum = new RankingFingerprintAccumulator();
        jdbc.query(connection -> {
            var statement = connection.prepareStatement("""
                SELECT product_id,score,ranking_position FROM weekly_rankings WHERE snapshot_id=? ORDER BY ranking_position
                """, ResultSet.TYPE_FORWARD_ONLY, ResultSet.CONCUR_READ_ONLY);
            statement.setString(1, id);
            statement.setFetchSize(Integer.MIN_VALUE);
            return statement;
        }, (RowCallbackHandler) row -> checksum.accept(row.getLong("product_id"), row.getLong("score"),
            row.getLong("ranking_position")));
        return new RankingCompletionEvidence(expected, mismatches, checksum.finish());
    }

    @Override
    public CompletedWeeklyRanking complete(UUID snapshotId, RankingResultFingerprint result) {
        jdbc.update("""
            INSERT INTO weekly_ranking_completions
                (snapshot_id,result_contract_version,product_count,result_checksum,completed_at)
            VALUES (?,?,?,?,UTC_TIMESTAMP(6))
            """, snapshotId.toString(), RankingResultFingerprint.CONTRACT_VERSION, result.count(), result.sha256());
        return findCompletion(snapshotId).orElseThrow(() -> new SnapshotIntegrityException("저장한 완성본이 없습니다."));
    }

    @Override
    public void advancePointer(RankingSnapshot snapshot) {
        int updated = jdbc.update("""
            UPDATE weekly_ranking_publications SET snapshot_id=?,revision=?
            WHERE period_start=? AND (snapshot_id IS NULL OR revision<?)
            """, snapshot.snapshotId().toString(), snapshot.revision(), snapshot.period().start(), snapshot.revision());
        if (updated != 1) {
            throw new SnapshotConflictException("공개 pointer를 더 높은 revision으로 전환하지 못했습니다.");
        }
    }
}
