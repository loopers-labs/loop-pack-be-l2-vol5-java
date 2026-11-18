package com.loopers.batch.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * W9 테스트에서 랭킹 테이블을 읽는다. 기대값과 비교할 값만 꺼낸다.
 */
public class RankingTables {

    private final JdbcTemplate jdbc;

    public RankingTables(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    /** 상품 ID 순. rank_no가 없으면 null */
    public List<ScoreRow> scores(String snapshotId) {
        return jdbc.query("SELECT product_id, score, rank_no FROM weekly_ranking WHERE snapshot_id = ? ORDER BY product_id",
            (rs, rowNum) -> new ScoreRow(rs.getLong("product_id"), rs.getLong("score"), (Integer) rs.getObject("rank_no")),
            snapshotId);
    }

    public Optional<String> snapshotStatus(String snapshotId) {
        return jdbc.queryForList("SELECT status FROM ranking_snapshot WHERE snapshot_id = ?", String.class, snapshotId).stream().findFirst();
    }

    public Optional<PublicationRow> publication(LocalDate periodStart) {
        return jdbc.query("SELECT snapshot_id, revision, published_at FROM ranking_publication WHERE period_start = ?",
            (rs, rowNum) -> new PublicationRow(rs.getString("snapshot_id"), rs.getLong("revision"), rs.getObject("published_at", LocalDateTime.class)),
            periodStart).stream().findFirst();
    }

    /**
     * 결과 checksum (발제 §6): 순위 순으로 상품 ID ⇥ 점수 ⇥ 순위 ↵ 를 이어 붙인 글자의 SHA-256(hex). 실행 ID·시각은 넣지 않는다.
     */
    public String resultChecksum(String snapshotId) {
        StringBuilder text = new StringBuilder();
        jdbc.query("SELECT product_id, score, rank_no FROM weekly_ranking WHERE snapshot_id = ? ORDER BY rank_no",
            rs -> {
                text.append(rs.getLong("product_id")).append('\t').append(rs.getLong("score")).append('\t').append(rs.getInt("rank_no")).append('\n');
            },
            snapshotId);
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public long count(String table) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    public record ScoreRow(long productId, long score, Integer rankNo) {}

    public record PublicationRow(String snapshotId, long revision, LocalDateTime publishedAt) {}
}
