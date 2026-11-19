package com.loopers.infrastructure.ranking;

import com.loopers.domain.ranking.RankingLaunchRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.util.OptionalLong;

@Repository
public class JdbcRankingLaunchRepository implements RankingLaunchRepository {

    private final JdbcTemplate jdbc;

    public JdbcRankingLaunchRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(dataSource);
    }

    @Override
    public OptionalLong findMaxSnapshotRevision(LocalDate periodStart) {
        Long max = jdbc.queryForObject("SELECT MAX(revision) FROM ranking_snapshot WHERE period_start = ?",
            Long.class, periodStart);
        return max == null ? OptionalLong.empty() : OptionalLong.of(max);
    }
}
