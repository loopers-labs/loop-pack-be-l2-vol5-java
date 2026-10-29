package com.loopers.interfaces.consumer;

import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import com.loopers.utils.DatabaseCleanUp;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 과제 6절 "실제 Spring listener 의 실패 경로" (📄 p.7). 별도 격리 topic/group 에서 DB commit 전 오류를 주입해
 * 활동·완료 기록 전체 rollback, 실패 record 이후로 offset 미진행, 중단형 error handler 의 컨테이너 중단을 확인하고,
 * 오류를 제거한 뒤 같은 group 으로 컨테이너를 재시작해 재전달·정상 저장·ack 까지 본다. 모든 대기는 유한한 deadline 이다.
 */
@SpringBootTest(properties = {
    "purchase-activity.topic=" + OrderConfirmedListenerFailureTest.TOPIC,
    "purchase-activity.group-id=" + OrderConfirmedListenerFailureTest.GROUP
})
class OrderConfirmedListenerFailureTest {

    static final String TOPIC = "order-events-OrderConfirmedListenerFailureTest";
    static final String GROUP = "purchase-activity-OrderConfirmedListenerFailureTest";
    private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
    private static final Duration DEADLINE = Duration.ofSeconds(20);
    private static final long ORDER_ID = 47L;
    /** 실패 record 앞에 정상 처리될 주문. committed offset 을 실제 값으로 만들어 auto.offset.reset 과 무관하게 "미진행" 을 본다. */
    private static final String ORDER_46_JSON = """
        {"eventType": "OrderConfirmed", "schemaVersion": 1, "eventId": "order-confirmed:v1:46", "orderId": 46,
         "confirmedAt": "2026-09-07T01:00:00.123Z", "items": [{"productId": 7, "quantity": 2}]}
        """;
    private static final String ORDER_47_JSON = """
        {"eventType": "OrderConfirmed", "schemaVersion": 1, "eventId": "order-confirmed:v1:47", "orderId": 47,
         "confirmedAt": "2026-09-07T01:00:00.123Z", "items": [{"productId": 7, "quantity": 1}, {"productId": 99, "quantity": 1}]}
        """;

    private static final AdminClient ADMIN;

    static {
        KafkaTestContainersConfig.createTopic(TOPIC, 1);
        ADMIN = AdminClient.create(Map.of(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers()));
    }

    @MockitoSpyBean
    private PurchaseActivityRepository repositorySpy;

    @Autowired
    private KafkaListenerEndpointRegistry registry;
    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @AfterAll
    static void closeAdmin() {
        ADMIN.close();
    }

    @DisplayName("DB commit 전 오류가 계속 나면 전체 rollback·offset 미진행·컨테이너 중단이고, 오류 제거 후 같은 group 으로 재시작하면 같은 record 가 재전달돼 저장·ack 된다.")
    @Test
    void stopsOnFailure_andRedeliversAfterRestart() throws Exception {
        // 준비 1 - 정상 주문 46 을 먼저 처리해 committed offset 을 실제 값으로 만든다 (reset 정책이 아니라 저장된 group offset 으로 재전달을 본다)
        MessageListenerContainer container = container();
        assertThat(container.isRunning()).isTrue();
        RecordMetadata healthy = send("46", ORDER_46_JSON);
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(healthy.offset() + 1));
        assertThat(repositorySpy.findActivitiesByOrderId(46L)).hasSize(1);

        // 준비 2 - 상품 99 저장 시점(둘째 품목, 장부·첫 품목 뒤)에 오류 주입
        doAnswer(invocation -> {
            ProductPurchaseActivity activity = invocation.getArgument(0);
            if (activity.getProductId() == 99L) {
                throw new RuntimeException("실험: DB commit 전 품목 저장 실패");
            }
            return invocation.callRealMethod();
        }).when(repositorySpy).saveActivity(any());

        // 실패 경로 - 발행 → 재시도 소진 → 중단형 error handler 가 컨테이너를 멈춘다 (sleep 없이 isRunning=false 까지 유한 대기)
        RecordMetadata target = send(String.valueOf(ORDER_ID), ORDER_47_JSON);
        long n = target.offset();
        assertThat(n).isEqualTo(healthy.offset() + 1);
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200)).until(() -> !container.isRunning());

        // 확인 - 활동·완료 기록 전체 rollback, committed offset == 실패 record 의 offset n (그 앞의 정상 record 까지만 완료), 시도 횟수 = 1 + 재시도 2 (test profile)
        assertThat(repositorySpy.findProcessedByOrderId(ORDER_ID)).isEmpty();
        assertThat(repositorySpy.findActivitiesByOrderId(ORDER_ID)).isEmpty();
        assertThat(committedOffset()).isEqualTo(n);
        verify(repositorySpy, times(3)).saveProcessed(argThat(p -> p.getOrderId() == ORDER_ID));

        // 오류 제거 - 저장이 정상으로 돌아온다
        doCallRealMethod().when(repositorySpy).saveActivity(any());

        // 재시작 - 같은 group 으로 같은 컨테이너를 다시 시작. seek·offset reset 없음
        container.start();
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200)).until(container::isRunning);
        await().atMost(DEADLINE).pollInterval(Duration.ofMillis(200))
            .untilAsserted(() -> assertThat(committedOffset()).isEqualTo(n + 1));

        // 확인 - 같은 record 가 1회 재전달돼 장부 1행·품목 2행이 저장되고 ack 됐다. 앞의 정상 주문 46 은 다시 처리되지 않았다
        verify(repositorySpy, times(4)).saveProcessed(argThat(p -> p.getOrderId() == ORDER_ID));
        verify(repositorySpy, times(1)).saveProcessed(argThat(p -> p.getOrderId() == 46L));
        assertThat(repositorySpy.findActivitiesByOrderId(46L)).hasSize(1);
        ProcessedOrderConfirmation processed = repositorySpy.findProcessedByOrderId(ORDER_ID).orElseThrow();
        assertThat(processed.getEventId()).isEqualTo("order-confirmed:v1:47");
        List<ProductPurchaseActivity> activities = repositorySpy.findActivitiesByOrderId(ORDER_ID);
        assertThat(activities).extracting(ProductPurchaseActivity::getProductId).containsExactly(7L, 99L);
        assertThat(activities).extracting(ProductPurchaseActivity::getQuantity).containsExactly(1, 1);
        assertThat(container.isRunning()).isTrue();
    }

    private MessageListenerContainer container() {
        return registry.getListenerContainers().stream()
            .filter(c -> GROUP.equals(c.getGroupId()))
            .findFirst()
            .orElseThrow();
    }

    private static RecordMetadata send(String key, String json) throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainersConfig.bootstrapServers());
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        try (KafkaProducer<String, byte[]> producer = new KafkaProducer<>(props)) {
            return producer.send(new ProducerRecord<>(TOPIC, key, json.getBytes(StandardCharsets.UTF_8))).get(10, TimeUnit.SECONDS);
        }
    }

    /** group 이 commit 한 다음 읽을 위치. 아직 commit 이 없으면 -1. */
    private static long committedOffset() throws Exception {
        OffsetAndMetadata committed = ADMIN.listConsumerGroupOffsets(GROUP)
            .partitionsToOffsetAndMetadata().get(10, TimeUnit.SECONDS).get(PARTITION);
        return committed == null ? -1 : committed.offset();
    }
}
