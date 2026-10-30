package com.loopers.testsupport;

import com.loopers.config.OrderActivityKafkaConfig;
import com.loopers.confg.kafka.KafkaConfig;
import com.loopers.interfaces.consumer.OrderActivityKafkaConsumer;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * 구매 활동 listener 검증(C6~C8) 컨텍스트. C1~C5 의 DB 컨텍스트에 운영과 같은 Kafka 설정(공통 KafkaConfig·자동 설정)과
 * 전용 listener 설정·listener 만 더한다. demo listener 는 import 하지 않아 이 컨텍스트에 없다.
 */
@Configuration(proxyBeanMethods = false)
@Import({PurchaseActivityDbTestConfig.class, KafkaConfig.class, OrderActivityKafkaConfig.class, OrderActivityKafkaConsumer.class})
@ImportAutoConfiguration({KafkaAutoConfiguration.class, JacksonAutoConfiguration.class})
public class OrderActivityKafkaTestConfig {
}
