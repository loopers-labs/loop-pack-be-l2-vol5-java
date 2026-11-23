package com.loopers.batch.job.ranking;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@Conditional(WeeklyRankingRecoveryRequested.class)
public class WeeklyRankingRecoverySafetyConfig {
    @Bean
    public static BeanFactoryPostProcessor weeklyRankingRecoverySafety(Environment environment) {
        return beanFactory -> {
            require(environment, "spring.batch.job.name", WeeklyRankingJobConfig.JOB_NAME);
            require(environment, "spring.batch.job.enabled", "false");
            require(environment, "spring.batch.jdbc.initialize-schema", "never");
            require(environment, "spring.jpa.hibernate.ddl-auto", "none");
            require(environment, "weekly-ranking.recovery.termination-confirmed", "true");
        };
    }

    private static void require(Environment environment, String property, String expected) {
        if (!expected.equalsIgnoreCase(environment.getProperty(property))) {
            throw new IllegalStateException("recovery requires " + property + "=" + expected);
        }
    }
}
