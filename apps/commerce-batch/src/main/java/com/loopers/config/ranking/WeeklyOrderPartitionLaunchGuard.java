package com.loopers.config.ranking;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

public final class WeeklyOrderPartitionLaunchGuard implements EnvironmentPostProcessor, Ordered {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        boolean scheduler = environment.acceptsProfiles(Profiles.of("ranking-scheduler"));
        boolean preserve = environment.acceptsProfiles(Profiles.of("ranking-preserve & !local & !test"));
        boolean orderPartition = "weeklyRankingOrderPartition".equals(environment.getProperty("spring.batch.job.name"));
        if (!scheduler && !orderPartition) {
            return;
        }
        boolean bootLaunch = environment.getProperty("spring.batch.job.enabled", boolean.class, true);
        if (!preserve || !orderPartition || bootLaunch) {
            throw new IllegalStateException("주문 파티션 랭킹은 ranking-preserve와 명시적 주문 파티션 Job 실행이 필요합니다.");
        }
    }

    @Override
    public int getOrder() {
        return ConfigDataEnvironmentPostProcessor.ORDER + 2;
    }
}
