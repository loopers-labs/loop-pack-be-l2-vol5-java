package com.loopers.config.pg;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
public class PgWebConfig {
    @Bean
    public ThreadPoolTaskScheduler pgResponseScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("pg-response-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean
    public URI pgCallbackUri(@Value("${pg.callback-url}") String value) {
        URI uri = URI.create(value);
        if (!"http".equals(uri.getScheme()) || !"127.0.0.1".equals(uri.getHost())
                || uri.getUserInfo() != null || uri.getFragment() != null || uri.getPort() < 1) {
            throw new IllegalArgumentException("PG callback은 설정된 로컬 HTTP 주소만 사용합니다.");
        }
        return uri;
    }
}
