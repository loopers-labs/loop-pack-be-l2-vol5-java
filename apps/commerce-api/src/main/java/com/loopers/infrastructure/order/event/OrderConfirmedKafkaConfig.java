package com.loopers.infrastructure.order.event;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.errors.TopicExistsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Configuration
@Profile({"local", "test"})
public class OrderConfirmedKafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedKafkaConfig.class);

    @Bean
    ApplicationRunner orderEventsTopicInitializer(KafkaAdmin kafkaAdmin,
                                                  @Value("${order.confirmed-event.topic}") String topic) {
        return args -> {
            AdminClient client = AdminClient.create(kafkaAdmin.getConfigurationProperties());
            try {
                client.createTopics(List.of(TopicBuilder.name(topic).partitions(3).replicas(1).build()))
                    .all().get(5, TimeUnit.SECONDS);
            } catch (ExecutionException failure) {
                if (!(failure.getCause() instanceof TopicExistsException)) {
                    log.error("주문 확정 topic을 준비하지 못했습니다. topic={}", topic, failure.getCause());
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                log.error("주문 확정 topic 준비가 중단됐습니다. topic={}", topic, failure);
            } catch (TimeoutException | RuntimeException failure) {
                log.error("주문 확정 topic을 준비하지 못했습니다. topic={}", topic, failure);
            } finally {
                client.close(Duration.ofSeconds(1));
            }
        };
    }
}
