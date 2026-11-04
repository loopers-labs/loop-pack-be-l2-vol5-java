package com.loopers.infrastructure.order.support;

import com.loopers.config.OrderEventKafkaProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * W6 직접 발행 경로의 테스트 조립(설계 W7.4-1). 전역 component scan 대상이 되지 않도록 {@code @Configuration}·{@code @Component} 를
 * 붙이지 않는다. {@link W6PreparationContextInitializer} 가 refresh 전에 bean 정의로 등록하면 CCPP 가 @Bean 메서드를 lite 설정으로 해석한다.
 * bean 이름은 53f912b 의 component scan 이름(orderConfirmedEventListener·kafkaOrderEventPublisher·noOpOrderPublicationProbe)과 같다.
 */
public class W6OrderEventTestConfiguration {

    public static final String RECORDER = "w6OrderConfirmedRecorder";
    public static final String LISTENER = "orderConfirmedEventListener";
    public static final String PORT = "kafkaOrderEventPublisher";
    public static final String PROBE = "noOpOrderPublicationProbe";

    @Bean(RECORDER)
    W6OrderConfirmedRecorder w6OrderConfirmedRecorder(ApplicationEventPublisher eventPublisher) {
        return new W6OrderConfirmedRecorder(eventPublisher);
    }

    @Bean(PROBE)
    W6OrderPublicationProbe noOpOrderPublicationProbe() {
        return new W6OrderPublicationProbe.NoOp();
    }

    @Bean(PORT)
    W6KafkaOrderEventPublisher kafkaOrderEventPublisher(
        @Qualifier(W6OrderEventKafkaConfig.ORDER_EVENT_KAFKA_TEMPLATE) KafkaTemplate<String, Object> template,
        OrderEventKafkaProperties properties) {
        return new W6KafkaOrderEventPublisher(template, properties);
    }

    @Bean(LISTENER)
    W6OrderConfirmedEventListener orderConfirmedEventListener(W6OrderPublicationProbe probe, W6OrderEventPublisher publisher,
                                                              @Value("${commerce.order-events.topic}") String topic) {
        return new W6OrderConfirmedEventListener(probe, publisher, topic);
    }
}
