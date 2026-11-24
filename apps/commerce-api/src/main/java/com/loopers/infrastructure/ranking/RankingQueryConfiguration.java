package com.loopers.infrastructure.ranking;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
public class RankingQueryConfiguration {
    @Bean(destroyMethod = "close")
    RankingReadResource weeklyRankingReadResource(Environment environment) {
        String prefix = "commerce.ranking.query.";
        return new RankingReadResource(environment.getProperty(prefix + "enabled", Boolean.class, false),
            environment.getProperty(prefix + "jdbc-url"), environment.getProperty(prefix + "username"),
            environment.getProperty(prefix + "password"));
    }
}
