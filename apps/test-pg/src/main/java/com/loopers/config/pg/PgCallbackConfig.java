package com.loopers.config.pg;

import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.client.RestClient;

@Configuration
public class PgCallbackConfig {
    @Bean
    public ThreadPoolTaskScheduler pgCallbackScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("pg-callback-");
        scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
        scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
        return scheduler;
    }

    @Bean(destroyMethod = "close")
    public CloseableHttpClient pgCallbackHttpClient() {
        var connectionConfig = ConnectionConfig.custom().setConnectTimeout(Timeout.ofSeconds(1))
                .setSocketTimeout(Timeout.ofSeconds(2)).build();
        var connectionManager = PoolingHttpClientConnectionManagerBuilder.create()
                .setDefaultConnectionConfig(connectionConfig).build();
        var requestConfig = RequestConfig.custom().setConnectionRequestTimeout(Timeout.ofSeconds(1))
                .setResponseTimeout(Timeout.ofSeconds(2)).build();
        return HttpClients.custom().setConnectionManager(connectionManager).setDefaultRequestConfig(requestConfig)
                .disableAutomaticRetries().disableRedirectHandling().build();
    }

    @Bean
    public RestClient pgCallbackRestClient(CloseableHttpClient pgCallbackHttpClient) {
        return RestClient.builder().requestFactory(new HttpComponentsClientHttpRequestFactory(pgCallbackHttpClient)).build();
    }
}
