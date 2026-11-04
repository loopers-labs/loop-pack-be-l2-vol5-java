package com.loopers.interfaces.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loopers.application.purchase.OrderConfirmationHandler;
import com.loopers.confg.kafka.KafkaConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/** 실제 application.yml로 profile별 구독 등록을 확인한다. 기본(test)은 0개, local은 주문 listener만 등록한다. */
class ConsumerPreparationConfigurationTest {
    @Test
    void testProfileDoesNotRegisterAnyKafkaSubscriptions() {
        runner("test").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DemoKafkaConsumer.class);
            assertThat(context).hasSingleBean(OrderConfirmedRecordAdapter.class);
            assertThat(context).doesNotHaveBean(OrderConfirmedKafkaListener.class);
            assertThat(context).doesNotHaveBean(OrderConfirmedKafkaConfiguration.FACTORY);
            assertThat(context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers()).isEmpty();
            assertThat(context.getEnvironment().getProperty("demo-kafka.enabled", Boolean.class)).isFalse();
        });
    }

    @Test
    void localProfileRegistersOnlyTheOrderListenerWithThreeConsumers() {
        // 등록만 확인한다. 로컬 broker에 실제로 접속하지 않도록 시작은 막는다.
        runner("local").withPropertyValues("commerce.order-events.consumer.auto-startup=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(DemoKafkaConsumer.class);
            assertThat(context).hasSingleBean(OrderConfirmedKafkaListener.class);
            assertThat(context).hasBean(OrderConfirmedKafkaConfiguration.ERROR_HANDLER);
            var containers = context.getBean(KafkaListenerEndpointRegistry.class).getListenerContainers();
            assertThat(containers).hasSize(1);
            var container = (ConcurrentMessageListenerContainer<?, ?>) containers.iterator().next();
            assertThat(container.getListenerId()).isEqualTo(OrderConfirmedKafkaListener.ID);
            assertThat(container.getConcurrency()).isEqualTo(3);
        });
    }

    private ApplicationContextRunner runner(String profile) {
        return new ApplicationContextRunner()
            .withInitializer(new ConfigDataApplicationContextInitializer())
            .withPropertyValues("spring.config.location=classpath:application.yml", "spring.profiles.active=" + profile)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withBean(OrderConfirmationHandler.class, () -> mock(OrderConfirmationHandler.class))
            .withUserConfiguration(KafkaConfig.class, DemoKafkaConsumer.class,
                OrderConfirmedMessageParser.class, OrderConfirmedRecordAdapter.class,
                OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaErrorHandling.class,
                OrderConfirmedKafkaListener.class);
    }
}
