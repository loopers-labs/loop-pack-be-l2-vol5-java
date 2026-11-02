package com.loopers.testcontainers;

import org.springframework.context.annotation.Configuration;

/**
 * 테스트 JVM 하나에 broker 3대를 한 번만 띄워 모든 테스트 컨텍스트가 공유한다 (W6 plan.md D-5).
 * 테스트끼리는 topic suffix·group id로 격리한다.
 * <p>
 * 접속 정보는 클래스가 로드될 때 정한다 — RedisTestContainersConfig와 같은 이유로, KafkaAdmin·producer가 먼저 만들어진 컨텍스트가
 * kafka.yml의 localhost:19092(compose)로 붙지 않게 한다.
 */
@Configuration
public class KafkaTestContainersConfig {

    private static final KafkaCluster CLUSTER = KafkaCluster.start();

    static {
        System.setProperty("spring.kafka.bootstrap-servers", CLUSTER.bootstrapServers());
    }

    public static String bootstrapServers() {
        return CLUSTER.bootstrapServers();
    }
}
