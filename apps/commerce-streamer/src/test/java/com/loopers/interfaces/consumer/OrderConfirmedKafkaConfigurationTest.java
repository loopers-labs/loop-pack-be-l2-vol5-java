package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.confg.kafka.KafkaConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.ContainerProperties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class OrderConfirmedKafkaConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
        .withInitializer(new ConfigDataApplicationContextInitializer())
        .withPropertyValues("spring.config.location=classpath:application.yml", "spring.profiles.active=test",
            "commerce.order-events.consumer.enabled=true", "commerce.order-events.consumer.auto-startup=false")
        .withBean(ObjectMapper.class, ObjectMapper::new)
        .withBean(OrderConfirmedRecordAdapter.class, () -> mock(OrderConfirmedRecordAdapter.class))
        .withUserConfiguration(KafkaConfig.class, OrderConfirmedKafkaConfiguration.class,
            OrderConfirmedKafkaListener.class);

    @Test
    void registersDedicatedRecordFactoryAndListenerWithoutChangingTheSharedBatchFactory() {
        runner.withPropertyValues("commerce.order-events.consumer.concurrency=3")
            .withBean(OrderConfirmedKafkaConfiguration.ERROR_HANDLER, CommonErrorHandler.class,
                CommonContainerStoppingErrorHandler::new)
            .run(context -> {
                assertThat(context).hasNotFailed();
                var factory = context.getBean(OrderConfirmedKafkaConfiguration.FACTORY,
                    ConcurrentKafkaListenerContainerFactory.class);
                assertThat(factory.isBatchListener()).isFalse();
                assertThat(factory.getContainerProperties().getAckMode())
                    .isEqualTo(ContainerProperties.AckMode.BATCH);
                assertThat(factory.getContainerProperties().getAssignmentCommitOption())
                    .isEqualTo(ContainerProperties.AssignmentCommitOption.NEVER);
                var settings = factory.getConsumerFactory().getConfigurationProperties();
                assertThat(settings.get(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo(false);
                assertThat(settings.get(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
                assertThat(settings.get(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG)).isEqualTo(false);
                assertThat(settings.get(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG)).isEqualTo(StringDeserializer.class);
                assertThat(settings.get(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG))
                    .isEqualTo(ByteArrayDeserializer.class);

                var registry = context.getBean(KafkaListenerEndpointRegistry.class);
                var container = (ConcurrentMessageListenerContainer<?, ?>)
                    registry.getListenerContainer(OrderConfirmedKafkaListener.ID);
                assertThat(container).isNotNull();
                assertThat(container.getConcurrency()).isEqualTo(3);
                assertThat(container.getContainerProperties().getGroupId()).isEqualTo("purchase-activity");
                assertThat(container.getContainerProperties().getTopics()).containsExactly("order-events");
                assertThat(container.isRunning()).isFalse();
                var batch = context.getBean(KafkaConfig.BATCH_LISTENER, ConcurrentKafkaListenerContainerFactory.class);
                assertThat(batch.isBatchListener()).isTrue();
                assertThat(batch.getContainerProperties().getAckMode()).isEqualTo(ContainerProperties.AckMode.MANUAL);
            });
    }

    @Test
    void enablingWithoutADedicatedErrorHandlerFailsInsteadOfUsingDefaultRecovery() {
        runner.withPropertyValues("commerce.order-events.consumer.concurrency=3").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(NoSuchBeanDefinitionException.class)
                .hasStackTraceContaining(OrderConfirmedKafkaConfiguration.ERROR_HANDLER);
        });
    }

    @Test
    void enablingRequiresAnExplicitPerInstanceConsumerCount() {
        runner.withBean(OrderConfirmedKafkaConfiguration.ERROR_HANDLER, CommonErrorHandler.class,
            CommonContainerStoppingErrorHandler::new).run(context -> {
                assertThat(context).hasFailed();
                assertThat(context.getStartupFailure())
                    .hasStackTraceContaining("commerce.order-events.consumer.concurrency");
            });
    }
}
