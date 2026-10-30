package com.loopers.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.retry.RetryCallback;
import org.springframework.retry.RetryContext;
import org.springframework.retry.RetryListener;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.transaction.CannotCreateTransactionException;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class PurchaseActivityListenerConfig {

    public static final String FACTORY = "purchaseActivityListenerContainerFactory";
    public static final String LISTENER_ID = "purchase-activity";
    public static final String RETRY = "purchaseActivityRetry";

    private static final int MAX_ATTEMPTS = 3;
    private static final long BACKOFF_MILLIS = 1_000;

    @Bean(name = FACTORY)
    public ConcurrentKafkaListenerContainerFactory<String, byte[]> purchaseActivityListenerContainerFactory(
        KafkaProperties kafkaProperties
    ) {
        Map<String, Object> consumer = new HashMap<>(kafkaProperties.buildConsumerProperties());
        consumer.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumer.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, false);
        consumer.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);

        ConcurrentKafkaListenerContainerFactory<String, byte[]> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(new DefaultKafkaConsumerFactory<>(consumer));
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.BATCH);
        factory.getContainerProperties().setObservationEnabled(true);
        factory.setCommonErrorHandler(new CommonContainerStoppingErrorHandler());
        return factory;
    }

    @Bean(name = RETRY)
    public RetryTemplate purchaseActivityRetry() {
        return RetryTemplate.builder()
            .maxAttempts(MAX_ATTEMPTS)
            .fixedBackoff(BACKOFF_MILLIS)
            .retryOn(List.of(
                PessimisticLockingFailureException.class,
                QueryTimeoutException.class,
                CannotCreateTransactionException.class))
            .traversingCauses()
            .withListener(new RetryListener() {
                @Override
                public <T, E extends Throwable> void onError(RetryContext context, RetryCallback<T, E> callback,
                                                             Throwable throwable) {
                    log.warn("주문 이벤트 처리가 실패했다: attempt={}, cause={}",
                        context.getRetryCount(), throwable.getClass().getSimpleName(), throwable);
                }
            })
            .build();
    }
}
