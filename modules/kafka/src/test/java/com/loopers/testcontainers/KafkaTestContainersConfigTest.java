package com.loopers.testcontainers;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.common.Node;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * fixture 가 띄운 실제 broker 에 fixture 가 주입한 주소로 연결되는지 Spring 없이 확인한다.
 */
class KafkaTestContainersConfigTest {

    @DisplayName("fixture 가 주입한 주소로 AdminClient 가 broker 1개를 조회한다.")
    @Test
    void describesClusterWithInjectedBootstrapServers() throws Exception {
        String bootstrapServers = KafkaTestContainersConfig.bootstrapServers();

        assertThat(System.getProperty("spring.kafka.bootstrap-servers")).isEqualTo(bootstrapServers);
        assertThat(System.getProperty("spring.kafka.admin.properties.bootstrap.servers")).isEqualTo(bootstrapServers);

        try (AdminClient admin = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            Collection<Node> nodes = admin.describeCluster().nodes().get(10, TimeUnit.SECONDS);
            assertThat(nodes).hasSize(1);
        }
    }
}
