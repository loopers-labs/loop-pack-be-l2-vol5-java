package com.loopers.config.kafka;

import com.loopers.domain.activity.OrderConfirmationContractException;
import com.loopers.interfaces.consumer.InvalidOrderConfirmedMessageException;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.util.backoff.FixedBackOff;

/**
 * 구매 활동 소비용 record listener factory. 기존 batch factory(KafkaConfig.BATCH_LISTENER)는 그대로 둔다.
 * record 하나 = DB 트랜잭션 하나 = ack 하나. 공용 consumerFactory 는 enable.auto.commit=false, value 는 byte[] (kafka.yml).
 * 일시 오류는 1초 간격 2회 재시도, 소진되거나 계약 위반·형식 오류(재시도 불가)면 컨테이너를 멈추고 실패 record 의 offset 은 commit 하지 않는다.
 * fetch 설정은 Kafka 기본값(fetch.min.bytes=1, fetch.max.wait.ms=500)이다. batch factory 의 1MiB·5s 대기를 쓰지 않아 작은 메시지도 바로 읽힌다.
 */
@Configuration
public class OrderActivityKafkaConfig {
    public static final String ORDER_ACTIVITY_LISTENER = "orderActivityKafkaListenerContainerFactory";
    static final long RETRY_INTERVAL_MS = 1_000L;
    static final long RETRY_ATTEMPTS = 2L;

    @Bean(name = ORDER_ACTIVITY_LISTENER)
    public ConcurrentKafkaListenerContainerFactory<Object, Object> orderActivityKafkaListenerContainerFactory(
        ConsumerFactory<Object, Object> consumerFactory
    ) {
        ConcurrentKafkaListenerContainerFactory<Object, Object> factory = new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.setBatchListener(false);
        factory.setConcurrency(1);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        factory.setCommonErrorHandler(new RetryThenStopErrorHandler(
            new FixedBackOff(RETRY_INTERVAL_MS, RETRY_ATTEMPTS),
            OrderConfirmationContractException.class,
            InvalidOrderConfirmedMessageException.class
        ));
        return factory;
    }
}
