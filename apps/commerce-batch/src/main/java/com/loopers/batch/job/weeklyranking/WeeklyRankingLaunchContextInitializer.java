package com.loopers.batch.job.weeklyranking;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;

import java.util.Map;

/**
 * 명시 launch 일 때 bean·DB 초기화 전에 보존 설정을 확인한다(랭킹 설계 4.8). 값을 고쳐 주지 않고 기동을 거절한다.
 */
public class WeeklyRankingLaunchContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static final Map<String, String> REQUIRED = Map.of(
        "spring.batch.job.name", WeeklyRankingJobConfig.JOB_NAME,
        "spring.batch.job.enabled", "false",
        "spring.jpa.hibernate.ddl-auto", "none",
        "spring.batch.jdbc.initialize-schema", "never");

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        Environment environment = context.getEnvironment();
        if (!environment.getProperty("weekly-ranking.launch.enabled", Boolean.class, false)) {
            return;
        }
        REQUIRED.forEach((key, expected) -> {
            if (!expected.equals(environment.getProperty(key))) {
                throw new IllegalStateException("weeklyRanking 명시 실행에는 " + key + "=" + expected + " 가 필요합니다.");
            }
        });
    }
}
