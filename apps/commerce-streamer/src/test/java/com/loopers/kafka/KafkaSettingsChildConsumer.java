package com.loopers.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/** KafkaConsumerSettingsExperimentTest가 별도 JVM으로 띄워 강제 종료하는 consumer. close 없이 끝나는 장면에만 쓴다. */
final class KafkaSettingsChildConsumer {
    private KafkaSettingsChildConsumer() {}

    public static void main(String[] args) throws Exception {
        String bootstrap = args[0];
        String topic = args[1];
        String group = args[2];
        boolean autoCommit = Boolean.parseBoolean(args[3]);
        try (var consumer = new KafkaConsumer<String, String>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG, group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, autoCommit,
            ConsumerConfig.AUTO_COMMIT_INTERVAL_MS_CONFIG, 60_000,
            ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 6_000,
            ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, 1_000,
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(topic));
            boolean announced = false;
            while (true) {
                int received = consumer.poll(Duration.ofMillis(200)).count();
                if (!announced && received > 0) {
                    System.out.println("READY " + received);
                    System.out.flush();
                    announced = true;
                }
            }
        }
    }
}
