package com.loopers.batch.job.ranking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("weekly-ranking")
public record WeeklyRankingProperties(@DefaultValue("100") int chunkSize) {
    public WeeklyRankingProperties {
        if (chunkSize < 1) {
            throw new IllegalArgumentException("weekly-ranking.chunk-size must be positive: " + chunkSize);
        }
    }
}
