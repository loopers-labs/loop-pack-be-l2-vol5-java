package com.loopers.domains.ranking.app;

import com.loopers.domains.ranking.app.port.WeeklyRankingReader;
import java.time.LocalDate;
import org.springframework.stereotype.Service;

@Service
public class WeeklyRankingQueryService {
    private final WeeklyRankingReader weeklyRankingReader;

    public WeeklyRankingQueryService(WeeklyRankingReader weeklyRankingReader) {
        this.weeklyRankingReader = weeklyRankingReader;
    }

    public WeeklyRankingResult weekly(LocalDate periodStart, int limit) {
        return null;
    }
}
