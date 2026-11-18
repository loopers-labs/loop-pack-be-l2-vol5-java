package com.loopers.domains.ranking.out;

import com.loopers.domains.ranking.app.port.PublishedRanking;
import com.loopers.domains.ranking.app.port.WeeklyRankingReader;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class JdbcWeeklyRankingReader implements WeeklyRankingReader {
    private final JdbcTemplate jdbcTemplate;

    public JdbcWeeklyRankingReader(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<PublishedRanking> findPublished(LocalDate periodStart, int limit) {
        return Optional.empty();
    }
}
