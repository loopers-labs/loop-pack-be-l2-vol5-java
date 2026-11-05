package com.loopers.e2e;

import com.loopers.interfaces.consumer.DemoKafkaConsumer;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.FilterType;

/**
 * commerce-streamer 의 구매 활동 소비 context. 실제 listener factory(OrderActivityKafkaConfig)·parser·consumer·facade·저장소만 명시한 패키지에서 가져온다.
 * 공용 설정: JPA(데이터소스·엔티티·repository), Kafka(consumerFactory), Jackson. 데모 consumer 는 뺀다. 테이블은 만들지 않는다 (api context 가 만든다).
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@ComponentScan(basePackages = {
    "com.loopers.config.jpa",
    "com.loopers.confg.kafka",
    "com.loopers.config.jackson",
    "com.loopers.config.kafka",
    "com.loopers.interfaces.consumer",
    "com.loopers.application.activity",
    "com.loopers.domain.activity",
    "com.loopers.infrastructure.activity"
}, excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE, classes = DemoKafkaConsumer.class))
public class E2eStreamerApplication {
}
