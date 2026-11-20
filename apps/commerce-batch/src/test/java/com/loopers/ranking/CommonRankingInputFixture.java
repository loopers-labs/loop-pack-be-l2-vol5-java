package com.loopers.ranking;

import com.loopers.domain.ranking.model.vo.RankingPeriod;
import com.loopers.infrastructure.persistence.ranking.JdbcRankingSnapshotRepository;
import com.loopers.infrastructure.persistence.ranking.WeeklyInputFingerprints;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Prepares the plan's immutable input contract for focused tests with hand-picked order or product ranges. */
public final class CommonRankingInputFixture {
    private CommonRankingInputFixture() {}

    public static void copyAndSeal(JdbcTemplate jdbc, String id, RankingPeriod period, List<Long> cuts, int count) {
        JdbcRankingSnapshotRepository.copyInputs(jdbc, UUID.fromString(id), period);
        seal(jdbc, id, period, cuts, count);
    }

    static void seal(JdbcTemplate jdbc, String id, RankingPeriod period, List<Long> cuts, int count) {
        var inputs = WeeklyInputFingerprints.read(jdbc, UUID.fromString(id), period, cuts, count);
        jdbc.update("""
            UPDATE weekly_ranking_snapshots SET state='FROZEN',frozen_at=UTC_TIMESTAMP(6),input_count=?,input_checksum=?
            WHERE snapshot_id=? AND state='FREEZING' AND input_contract_version=3
            """, inputs.week().count(), inputs.week().sha256(), id);
        for (int index = 0; index < count; index++) {
            var partition = inputs.partitions().get(index);
            jdbc.update("""
                UPDATE weekly_ranking_order_partitions s JOIN weekly_ranking_snapshots m USING(snapshot_id)
                SET s.frozen_at=m.frozen_at,s.input_count=?,s.input_checksum=?
                WHERE s.snapshot_id=? AND s.order_partition_id=?
                """, partition.count(), partition.sha256(), id, index);
        }
    }
}
