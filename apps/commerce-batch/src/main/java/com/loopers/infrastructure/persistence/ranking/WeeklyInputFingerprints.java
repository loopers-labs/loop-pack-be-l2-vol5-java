package com.loopers.infrastructure.persistence.ranking;

import com.loopers.domain.ranking.model.vo.InputFingerprint;
import com.loopers.domain.ranking.model.vo.RankingPeriod;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

public record WeeklyInputFingerprints(InputFingerprint week, List<InputFingerprint> partitions) {
    /** Finite upper bounds separate the nonempty ranges. Additional partitions have no input rows. */
    public static WeeklyInputFingerprints read(JdbcTemplate jdbc, UUID snapshot, RankingPeriod period, List<Long> cuts, int partitionCount) {
        if (partitionCount <= cuts.size()) {
            throw new IllegalArgumentException("주문 파티션 경계 수가 파티션 수와 맞지 않습니다.");
        }
        var week = new PurchaseInputFingerprintAccumulator(period);
        var partitions = new ArrayList<PurchaseInputFingerprintAccumulator>(partitionCount);
        for (int index = 0; index < partitionCount; index++) {
            partitions.add(new PurchaseInputFingerprintAccumulator(period));
        }
        JdbcRankingSnapshotRepository.readInputs(jdbc, snapshot, new RowCallbackHandler() {
            private int index;

            @Override
            public void processRow(ResultSet row) throws SQLException {
                long order = row.getLong("order_id");
                while (index < cuts.size() && order >= cuts.get(index)) {
                    index++;
                }
                long product = row.getLong("product_id");
                int quantity = row.getInt("quantity");
                var confirmed = row.getObject("confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
                week.accept(order, product, quantity, confirmed);
                partitions.get(index).accept(order, product, quantity, confirmed);
            }
        });
        return new WeeklyInputFingerprints(week.finish(), partitions.stream().map(PurchaseInputFingerprintAccumulator::finish).toList());
    }
}