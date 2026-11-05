package com.loopers.config;

import java.lang.management.ManagementFactory;
import java.util.concurrent.atomic.AtomicReference;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import org.apache.kafka.clients.producer.Producer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

import static org.assertj.core.api.Assertions.assertThat;

class OrderEventsKafkaConfigTest {
    // @Value Duration 변환은 Spring Boot 애플리케이션이 등록하는 변환기에 기댄다.
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withInitializer(context -> context.getBeanFactory().setConversionService(ApplicationConversionService.getSharedInstance()))
        .withUserConfiguration(OrderEventsKafkaConfig.class)
        .withBean(KafkaProperties.class, KafkaProperties::new)
        .withPropertyValues(
            "commerce.kafka.producer.max-block=2s",
            "commerce.kafka.topics.order-events=order-events",
            "commerce.kafka.topics.order-events-partitions=1",
            "commerce.kafka.topics.order-event-republish-requests=order-event-republish-requests",
            "commerce.kafka.topics.order-event-republish-requests-partitions=1");

    // Kafka producer는 만들 때 app-info MBean을 등록하고 close할 때 해제한다. broker 연결 없이 실제 producer가 닫혔는지 본다.
    // 전제 단언이나 producer 생성이 실패해도 열린 producer가 남지 않게 정리를 바깥 finally에 둔다.
    @Test
    @DisplayName("컨텍스트를 닫으면 주문 이벤트용 producer factory가 정리되어 그 factory가 만든 Kafka producer가 닫힌다")
    void closesOrderEventsProducerOnContextClose() throws Exception {
        AtomicReference<ProducerFactory<?, ?>> producerFactory = new AtomicReference<ProducerFactory<?, ?>>();
        AtomicReference<ObjectName> appInfo = new AtomicReference<ObjectName>();
        try {
            contextRunner.run(context -> {
                KafkaTemplate<?, ?> template = context.getBean("orderEventsKafkaTemplate", KafkaTemplate.class);
                producerFactory.set(template.getProducerFactory());
                appInfo.set(appInfoName(producerFactory.get().createProducer()));
                assertThat(isRegistered(appInfo.get())).as("context를 닫기 전에는 producer가 열려 있다").isTrue();
            });

            assertThat(isRegistered(appInfo.get())).as("context를 닫은 뒤 주문 이벤트 producer가 닫혔는가").isFalse();
        } finally {
            if (producerFactory.get() instanceof DefaultKafkaProducerFactory<?, ?> leftOpen) {
                leftOpen.destroy();
            }
        }
    }

    private static ObjectName appInfoName(Producer<?, ?> producer) throws MalformedObjectNameException {
        String clientId = producer.metrics().keySet().iterator().next().tags().get("client-id");
        return new ObjectName("kafka.producer:type=app-info,id=" + clientId);
    }

    private static boolean isRegistered(ObjectName name) {
        return ManagementFactory.getPlatformMBeanServer().isRegistered(name);
    }
}
