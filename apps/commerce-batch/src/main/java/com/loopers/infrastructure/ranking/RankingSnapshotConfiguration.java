package com.loopers.infrastructure.ranking;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration(proxyBeanMethods = false)
public class RankingSnapshotConfiguration {
    @Bean("weeklyRankingClock")
    Clock weeklyRankingClock() {
        return Clock.systemUTC();
    }
}
