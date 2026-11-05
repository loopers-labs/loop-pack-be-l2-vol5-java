package com.loopers.domains.order.in;

import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationContext;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class OrderConfirmedProducerConfigTest extends OrderEventsIntegrationSupport {
    @Autowired private KafkaTemplate<String, String> orderEventsKafkaTemplate;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    @Autowired private ThreadPoolTaskScheduler taskScheduler;
    @Value("${commerce.outbox.relay.send-timeout}") private Duration relaySendTimeout;

    // C1: relay는 broker 확인을 send-timeout까지 기다린다. producer가 그보다 먼저 delivery timeout으로 결론을 내야
    // relay가 "결과 모름"으로 끝내는 일이 없다(D115). delivery.timeout.ms는 request.timeout.ms + linger.ms 이상이어야 producer가 시작된다.
    @Test
    @DisplayName("relay가 쓰는 주문 이벤트 producer의 delivery.timeout.ms는 30초로 relay 대기(35초)보다 짧고 request.timeout + linger 이상이며 acks=all·idempotence 켬이다")
    void deliveryTimeoutIsShorterThanRelayWait() {
        ProducerConfig config = new ProducerConfig(orderEventsKafkaTemplate.getProducerFactory().getConfigurationProperties());
        int delivery = config.getInt(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG);

        assertThat(delivery).isEqualTo(30_000);
        assertThat((long) delivery).isLessThan(relaySendTimeout.toMillis());
        assertThat(config.getInt(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG) + config.getLong(ProducerConfig.LINGER_MS_CONFIG))
            .isLessThanOrEqualTo(delivery);
        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
    }

    // C3: relay가 broker 장애로 최대 35초 기다려도 결제 재조회·만료 스케줄이 다른 스레드에서 돌게 한다(참모 보강 14:18).
    @Test
    @DisplayName("스케줄러 스레드 풀은 2개라 relay와 결제 스케줄 작업이 같은 스레드 하나를 두고 기다리지 않는다")
    void schedulerPoolHasTwoThreads() {
        assertThat(taskScheduler.getScheduledThreadPoolExecutor().getCorePoolSize()).isEqualTo(2);
    }

    @Test
    @DisplayName("주문 이벤트 producer의 실제 설정은 acks=all(-1)·idempotence 켬·max.block.ms 2초·문자열 값 직렬화다")
    void producerGuaranteesAndShortBlock() {
        ProducerConfig config = new ProducerConfig(orderEventsKafkaTemplate.getProducerFactory().getConfigurationProperties());

        assertThat(config.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(config.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(config.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(2000L);
        assertThat(config.getClass(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
        assertThat(config.getClass(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG)).isEqualTo(StringSerializer.class);
    }

    @Test
    @DisplayName("kafka-clients 3.8 기본값도 acks=all(-1)·idempotence 켬이다(명시 설정은 공통 설정 변경에 대비한 것)")
    void kafkaClientDefaultsMatch() {
        ProducerConfig defaults = new ProducerConfig(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));

        assertThat(defaults.getString(ProducerConfig.ACKS_CONFIG)).isEqualTo("-1");
        assertThat(defaults.getBoolean(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG)).isTrue();
        assertThat(defaults.getLong(ProducerConfig.MAX_BLOCK_MS_CONFIG)).isEqualTo(60_000L);
    }

    // 3b에서 재발행 명령 consumer가 생겨 "listener 0개"에서 "명령 consumer 하나만"으로 바뀌었다(설계 27절 대사와 재발행).
    @Test
    @DisplayName("commerce-api는 order-events·재발행 명령 topic을 설정한 partition 수·복제 1로 선언하고 Kafka listener는 재발행 명령 consumer 하나만 띄운다")
    void declaresTopicsWithOnlyRepublishListener() {
        assertThat(applicationContext.getBeansOfType(NewTopic.class).values())
            .extracting(NewTopic::name, NewTopic::numPartitions, NewTopic::replicationFactor)
            .containsExactlyInAnyOrder(tuple(TOPIC, 1, (short) 1), tuple(REQUEST_TOPIC, 1, (short) 1));
        assertThat(kafkaListenerEndpointRegistry.getListenerContainerIds())
            .containsExactly(RepublishRequestsListener.LISTENER_ID);
    }
}
