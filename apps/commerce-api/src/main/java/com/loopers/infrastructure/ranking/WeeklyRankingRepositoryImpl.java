package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.PublishedWeeklySnapshot;
import com.loopers.domain.ranking.WeeklyRankingEntry;
import com.loopers.domain.ranking.WeeklyRankingRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

/**
 * 랭킹 테이블(modules:ranking의 ranking-schema.sql)은 JPA 엔티티가 아니므로 SQL로 읽는다.
 * 시각은 UTC로 저장돼 있어 LocalDateTime으로 읽고 UTC Instant로 바꾼다(JVM 시간대를 거치지 않는다).
 */
@Component
public class WeeklyRankingRepositoryImpl implements WeeklyRankingRepository {

    private final JdbcTemplate jdbc;

    public WeeklyRankingRepositoryImpl(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public Optional<PublishedWeeklySnapshot> findPublished(LocalDate periodStart) {
        return jdbc.query("""
                select s.period_start, s.period_end, s.snapshot_id, s.revision, s.frozen_at
                from ranking_publication pub
                join ranking_snapshot s on s.snapshot_id = pub.snapshot_id
                where pub.period_start = ? and s.status = 'COMPLETED'""",
            (rs, rowNum) -> new PublishedWeeklySnapshot(
                rs.getObject("period_start", LocalDate.class),
                rs.getObject("period_end", LocalDate.class),
                rs.getString("snapshot_id"),
                rs.getInt("revision"),
                rs.getObject("frozen_at", LocalDateTime.class).toInstant(ZoneOffset.UTC)
            ),
            periodStart).stream().findFirst();
    }

    /** 삭제된 상품은 기존 고객 공개 조건(deleted_at is null)으로 먼저 거르고, 그다음 limit을 적용한다. 계산 순위는 다시 매기지 않는다. */
    @Override
    public List<WeeklyRankingEntry> findVisibleEntries(String snapshotId, int limit) {
        return jdbc.query("""
                select r.rank_no, r.product_id, r.score
                from weekly_ranking r
                join product p on p.id = r.product_id and p.deleted_at is null
                where r.snapshot_id = ?
                order by r.rank_no
                limit ?""",
            (rs, rowNum) -> new WeeklyRankingEntry(rs.getInt("rank_no"), rs.getLong("product_id"), rs.getLong("score")),
            snapshotId, limit);
    }
}
