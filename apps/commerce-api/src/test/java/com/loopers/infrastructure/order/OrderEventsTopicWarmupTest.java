package com.loopers.infrastructure.order;

import com.loopers.CommerceApiApplication;
import org.apache.kafka.common.config.ConfigException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
class OrderEventsTopicWarmupTest {

    private static ConfigurableApplicationContext start(String... args) {
        String[] common = {"--spring.jpa.hibernate.ddl-auto=none", "--management.server.port=0"};
        return new SpringApplicationBuilder(CommerceApiApplication.class)
            .profiles("test")
            .web(WebApplicationType.NONE)
            .run(Stream.concat(Stream.of(common), Stream.of(args)).toArray(String[]::new));
    }

    @DisplayName("EVT-016: producer 설정이 잘못되면 첫 확정을 기다리지 않고 기동에서 실패한다")
    @Test
    void rejectsBrokenProducerConfigAtStartup() {
        assertThatThrownBy(() -> start("--spring.kafka.producer.acks=1").close())
            .isInstanceOf(ConfigException.class)
            .hasMessageContaining("acks");
    }

    @DisplayName("EVT-014: 기동 때 topic metadata 를 받지 못해도 기동은 끝난다. 첫 발행 때 다시 받는다")
    @Test
    void startsWithoutTopicMetadata() {
        try (ConfigurableApplicationContext app = start("--kafka.topics.order-events=order-events-absent")) {
            assertThat(app.isRunning()).isTrue();
        }
    }
}
