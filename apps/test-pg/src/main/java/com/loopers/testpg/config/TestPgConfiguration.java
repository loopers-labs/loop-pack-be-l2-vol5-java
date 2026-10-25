package com.loopers.testpg.config;

import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.loopers.testpg.application.LookupScenarioService;
import com.loopers.testpg.application.PaymentCallback;
import com.loopers.testpg.application.PaymentScheduler;
import com.loopers.testpg.application.PaymentService;
import com.loopers.testpg.application.ProcessingOptions;
import com.loopers.testpg.domain.LookupScenarioRepository;
import com.loopers.testpg.domain.PaymentRepository;
import com.loopers.testpg.domain.PaymentStatsRepository;
import com.loopers.testpg.domain.ScenarioRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.concurrent.Executor;

@Configuration
@EnableConfigurationProperties(TestPgProperties.class)
public class TestPgConfiguration {
    @Bean
    LookupScenarioService lookupScenarioService(LookupScenarioRepository repository) {
        return new LookupScenarioService(repository);
    }

    @Bean
    PaymentService paymentService(PaymentRepository repository, ScenarioRepository scenarios,
                                  PaymentStatsRepository stats, PaymentScheduler scheduler,
                                  PaymentCallback callback, @Qualifier("paymentCallbackExecutor") Executor callbackExecutor,
                                  TestPgProperties properties) {
        return new PaymentService(repository, scenarios, stats, scheduler, callback, callbackExecutor, new ProcessingOptions(
            properties.processingMode(), properties.processingDelay(), properties.dropResponseDelay()
        ));
    }

    @Bean
    ThreadPoolTaskScheduler paymentTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-pg-completion-");
        return scheduler;
    }

    @Bean
    ThreadPoolTaskExecutor paymentCallbackExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("test-pg-callback-");
        return executor;
    }

    @Bean
    Jackson2ObjectMapperBuilderCustomizer pgJsonTypes() {
        return builder -> builder.postConfigurer(mapper -> {
            mapper.coercionConfigFor(LogicalType.Integer)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.String, CoercionAction.Fail);
            mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        });
    }
}
