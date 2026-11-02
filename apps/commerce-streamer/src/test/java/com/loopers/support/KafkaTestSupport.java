package com.loopers.support;

import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.TopicExistsException;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * streamer 테스트용 Kafka 도우미. order-events는 api가 만드는 topic이라 streamer 테스트 클러스터에는 직접 만든다.
 */
public final class KafkaTestSupport {

    private KafkaTestSupport() {}

    public static Admin admin() {
        return Admin.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()));
    }

    public static void ensureTopic(String topic, int partitions) {
        try (Admin admin = admin()) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 3).configs(Map.of("min.insync.replicas", "2"))))
                .all().get(30, TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            if (!(e.getCause() instanceof TopicExistsException)) {
                throw new IllegalStateException(e);
            }
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * group의 committed offset. 없으면 null — "아직 commit한 적 없음".
     */
    public static Long committedOffset(String groupId, TopicPartition partition) {
        try (Admin admin = admin()) {
            Map<TopicPartition, OffsetAndMetadata> offsets = admin.listConsumerGroupOffsets(groupId)
                .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS);
            OffsetAndMetadata committed = offsets.get(partition);
            return committed == null ? null : committed.offset();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 주문 확정 이벤트 원문 (견본과 같은 모양, 필드 순서도 같다).
     */
    public static String orderConfirmedJson(String eventId, long orderId, String confirmedAt, long[][] items) {
        StringBuilder json = new StringBuilder()
            .append("{\"eventId\":\"").append(eventId).append("\",\"eventType\":\"OrderConfirmed\",\"schemaVersion\":1,")
            .append("\"orderId\":").append(orderId).append(",\"confirmedAt\":\"").append(confirmedAt).append("\",\"items\":[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                json.append(',');
            }
            json.append("{\"productId\":").append(items[i][0]).append(",\"quantity\":").append(items[i][1]).append('}');
        }
        return json.append("]}").toString();
    }
}
