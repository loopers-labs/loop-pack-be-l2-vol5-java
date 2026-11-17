package com.loopers.batch.job.weeklyranking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** 명시 launch 옵션(랭킹 설계 4.7). launch.enabled=false 이면 나머지 값이 없어도 된다. */
@ConfigurationProperties(prefix = "weekly-ranking", ignoreUnknownFields = false)
public record WeeklyRankingLaunchProperties(@DefaultValue Launch launch, String mode, String periodStart, Long executionId) {

    public record Launch(@DefaultValue("false") boolean enabled) {}
}
