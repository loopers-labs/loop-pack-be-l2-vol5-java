package com.loopers.logging;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Logger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;

@SpringBootTest(classes = OrderEventLoggingTest.Config.class, webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "spring.config.import=classpath:logging.yml")
class OrderEventLoggingTest {
    @Configuration(proxyBeanMethods = false)
    static class Config {}

    @ParameterizedTest
    @ValueSource(strings = {"com.loopers.application.order.OrderOutboxRelay",
            "com.loopers.purchaseactivity.interfaces.OrderConfirmedListener"})
    void 발행과_소비_로그는_비동기_큐를_거쳐_콘솔에_한_번_전달한다(String name) {
        var logger = (Logger) LoggerFactory.getLogger(name);
        assertThat(logger.isAdditive()).isFalse();
        assertThat(logger.iteratorForAppenders()).toIterable().singleElement()
                .isInstanceOfSatisfying(AsyncAppender.class, async -> {
                    assertThat(async.isStarted()).isTrue();
                    assertThat(async.getAppender("CONSOLE").isStarted()).isTrue();
                    assertThat(async.getDiscardingThreshold()).isZero();
                    assertThat(async.isNeverBlock()).isFalse();
                });
    }
}
