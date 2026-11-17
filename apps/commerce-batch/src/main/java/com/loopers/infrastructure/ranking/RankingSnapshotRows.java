package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingInputException;
import com.loopers.domain.ranking.RankingInputManifest;
import com.loopers.domain.ranking.SnapshotStatus;
import org.springframework.jdbc.core.RowMapper;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** ranking_snapshot 조회 SQL 과 manifest 매핑. 날짜·시각은 getObject 로 UTC 필드 그대로 읽는다. */
final class RankingSnapshotRows {

    static final String SELECT = "SELECT snapshot_id, period_start, period_end, revision, status, input_fixed_at, "
        + "input_count, product_count, input_checksum, result_checksum, completed_at, deleted_at FROM ranking_snapshot ";

    static final RowMapper<RankingInputManifest> MAPPER = (rs, rowNum) -> new RankingInputManifest(
        rs.getString("snapshot_id"),
        rs.getObject("period_start", LocalDate.class),
        rs.getObject("period_end", LocalDate.class),
        rs.getLong("revision"),
        status(rs.getString("status")),
        rs.getObject("input_fixed_at", LocalDateTime.class),
        rs.getLong("input_count"),
        rs.getLong("product_count"),
        rs.getString("input_checksum"),
        rs.getString("result_checksum"),
        rs.getObject("completed_at", LocalDateTime.class),
        rs.getObject("deleted_at") != null
    );

    private RankingSnapshotRows() {}

    private static SnapshotStatus status(String value) {
        try {
            return SnapshotStatus.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new RankingInputException(RankingInputException.Reason.INCONSISTENT_INPUT, "알 수 없는 snapshot 상태입니다.");
        }
    }
}
