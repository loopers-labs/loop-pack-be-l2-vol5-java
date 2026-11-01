package com.loopers.interfaces.consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/** demo listener 는 기본 실행에서 그대로 등록되고, demo-kafka.enabled=false 를 명시한 실행에서만 bean 자체가 없다. */
@DisplayName("demo listener 는 기본 등록되고 demo-kafka.enabled=false 일 때만 빠진다.")
class DemoKafkaConsumerConditionTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(DemoKafkaConsumer.class);

    @DisplayName("설정이 없으면 등록된다.")
    @Test
    void registeredByDefault() {
        runner.run(context -> assertThat(context).hasSingleBean(DemoKafkaConsumer.class));
    }

    @DisplayName("true 면 등록되고 false 면 등록되지 않는다.")
    @Test
    void explicitSwitch() {
        runner.withPropertyValues("demo-kafka.enabled=true").run(context -> assertThat(context).hasSingleBean(DemoKafkaConsumer.class));
        runner.withPropertyValues("demo-kafka.enabled=false").run(context -> assertThat(context).doesNotHaveBean(DemoKafkaConsumer.class));
    }
}
