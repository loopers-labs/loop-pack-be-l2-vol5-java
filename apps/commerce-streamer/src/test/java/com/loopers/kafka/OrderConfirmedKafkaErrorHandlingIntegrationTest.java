package com.loopers.kafka;

import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.infrastructure.purchase.PurchaseActivityRepositoryImpl;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaConfiguration;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaErrorHandling;
import com.loopers.interfaces.consumer.OrderConfirmedKafkaListener;
import com.loopers.testcontainers.KafkaTestBroker;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConsumerAwareRebalanceListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

/**
 * 업무용 오류 처리기를 실제 @KafkaListener에 연결해 경로를 차례로 확인한다.
 * 메시지 오류·사실 충돌은 DLQ 후 계속, DB 오류는 간격을 늘리며 재시도하고 총 대기 시간 안에 풀리면 스스로 이어 간다.
 * 총 대기 시간을 넘기면 중단하고, DLQ 전송 실패도 중단한다. 중단 뒤에는 원인을 없애고 같은 group으로 다시 시작한다.
 * 재시도 값은 테스트용으로 짧게 넣었다(운영: 1초부터 2배, 최대 간격 30초, 총 5분).
 * max.poll.interval.ms를 4초로 줄였다. 한 간격(최대 2초)이 그보다 짧으면, 총 대기(최대 10초)가 그보다 길어도
 * 간격마다 poll이 다시 일어나 group에서 빠지지 않는지(revoke 0회) 본다. DB가 빠르게 예외를 던지는 조건이다.
 * 주문용 factory의 max.poll.interval.ms 기본값은 5분이며, 처리·대기를 합한 실제 poll 사이 시간을 봐야 한다.
 */
@SpringBootTest(classes = {KafkaFailureHandlingExperimentTest.DatabaseConfig.class,
    OrderConfirmedKafkaConfiguration.class, OrderConfirmedKafkaErrorHandling.class,
    OrderConfirmedKafkaListener.class}, properties = {
        "commerce.order-events.consumer.enabled=true", "commerce.order-events.consumer.concurrency=1",
        "commerce.order-events.consumer.auto-startup=false",
        "commerce.order-events.consumer.retry.initial-interval=500ms",
        "commerce.order-events.consumer.retry.multiplier=2",
        "commerce.order-events.consumer.retry.max-interval=2s",
        "commerce.order-events.consumer.retry.max-elapsed-time=10s",
        "spring.kafka.consumer.properties.max.poll.interval.ms=4000"})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderConfirmedKafkaErrorHandlingIntegrationTest extends KafkaPurchaseExperimentSupport {
    private static final KafkaTestBroker BROKER = new KafkaTestBroker();
    private static final String TOPIC = "order-error-handling-" + UUID.randomUUID();
    private static final String DLT = TOPIC + "-dlt";
    private static final String GROUP = "purchase-activity";
    private static final TopicPartition SOURCE = new TopicPartition(TOPIC, 0);

    @Autowired private KafkaListenerEndpointRegistry registry;
    @MockitoSpyBean private PurchaseActivityRepositoryImpl activityRepository;

    @DynamicPropertySource
    static void kafkaProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.kafka.bootstrap-servers", BROKER::bootstrapServers);
        properties.add("commerce.order-events.topic", () -> TOPIC);
    }

    @Override
    @BeforeAll
    void startExperimentBroker() {
        broker = BROKER;
    }

    @AfterAll
    void stopSubscriptions() {
        registry.stop();
    }

    @Test
    @Timeout(150)
    void quarantinesMessageProblemsAndStopsOnDatabaseOrDeadLetterFailure() throws Exception {
        var transientDownUntil = new AtomicLong();
        var persistentDown = new AtomicBoolean();
        List<Long> transientFailures = new CopyOnWriteArrayList<>();
        List<Long> persistentFailures = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            ProductPurchaseActivity activity = invocation.getArgument(0);
            if (activity.getOrderId().equals(44L) && System.nanoTime() < transientDownUntil.get()) {
                transientFailures.add(System.nanoTime());
                throw new TransientDataAccessResourceException("test: DB briefly unavailable");
            }
            if (activity.getOrderId().equals(46L) && persistentDown.get()) {
                persistentFailures.add(System.nanoTime());
                throw new TransientDataAccessResourceException("test: DB unavailable");
            }
            return invocation.callRealMethod();
        }).when(activityRepository).save(any());
        var container = registry.getListenerContainer(OrderConfirmedKafkaListener.ID);
        assertThat(container).isNotNull();
        var revocations = new AtomicInteger();
        container.getContainerProperties().setConsumerRebalanceListener(new ConsumerAwareRebalanceListener() {
            @Override
            public void onPartitionsRevokedBeforeCommit(Consumer<?, ?> consumer, Collection<TopicPartition> partitions) {
                revocations.incrementAndGet();
            }
        });

        try (var admin = admin(); var producer = producer()) {
            admin.createTopics(List.of(new NewTopic(TOPIC, 1, (short) 1), new NewTopic(DLT, 1, (short) 1)))
                .all().get(10, TimeUnit.SECONDS);
            seedOffset(admin, GROUP, SOURCE);

            // 1) 메시지 오류와 사실 충돌은 DLQ로 보내고 뒤의 정상 주문을 계속 처리한다.
            assertThat(sendOrder(producer, TOPIC, 42L).offset()).isZero();
            assertThat(send(producer, "42", payload(42L, 6)).offset()).isEqualTo(1);
            assertThat(send(producer, "99", "not-json".getBytes(StandardCharsets.UTF_8)).offset()).isEqualTo(2);
            assertThat(sendOrder(producer, TOPIC, 43L).offset()).isEqualTo(3);
            container.start();
            await(() -> committed(admin, GROUP, SOURCE) == 4L);
            assertOrders(42L, 43L);
            assertThat(activities.findAll()).filteredOn(activity -> activity.getOrderId().equals(42L))
                .extracting(ProductPurchaseActivity::getQuantity).containsExactlyInAnyOrder(5, 3);
            assertThat(deadLetterOffsets()).containsExactly(1L, 2L);

            // 2) DB가 잠깐(6초, max.poll.interval 4초보다 길게) 실패하면 간격을 늘리며 재시도하다가 스스로 이어 간다.
            transientDownUntil.set(System.nanoTime() + Duration.ofSeconds(6).toNanos());
            assertThat(sendOrder(producer, TOPIC, 44L).offset()).isEqualTo(4);
            assertThat(sendOrder(producer, TOPIC, 45L).offset()).isEqualTo(5);
            await(() -> committed(admin, GROUP, SOURCE) == 6L);
            assertOrders(42L, 43L, 44L, 45L);
            assertThat(container.isRunning()).isTrue();
            assertThat(revocations).hasValue(0);
            assertThat(transientFailures).hasSizeGreaterThanOrEqualTo(4);
            assertThat(gaps(transientFailures)).allSatisfy(gap -> assertThat(gap).isLessThan(Duration.ofMillis(2_800)));
            assertThat(Duration.ofNanos(transientFailures.getLast() - transientFailures.getFirst()))
                .isGreaterThan(Duration.ofSeconds(4));

            // 3) 총 대기 시간(10초)을 넘겨도 실패하면 offset을 지키며 멈춘다. 그동안에도 group에서 빠지지 않았다.
            persistentDown.set(true);
            assertThat(sendOrder(producer, TOPIC, 46L).offset()).isEqualTo(6);
            assertThat(sendOrder(producer, TOPIC, 47L).offset()).isEqualTo(7);
            await(() -> !container.isRunning());
            assertThat(revocationsBeforeStop(revocations)).isZero();
            assertThat(Duration.ofNanos(persistentFailures.getLast() - persistentFailures.getFirst()))
                .isGreaterThanOrEqualTo(Duration.ofSeconds(9));
            assertThat(gaps(persistentFailures)).allSatisfy(gap -> assertThat(gap).isLessThan(Duration.ofMillis(2_800)));
            assertThat(committed(admin, GROUP, SOURCE)).isEqualTo(6L);
            assertOrders(42L, 43L, 44L, 45L);
            assertThat(deadLetterOffsets()).containsExactly(1L, 2L);

            persistentDown.set(false);
            container.start();
            await(() -> committed(admin, GROUP, SOURCE) == 8L);
            assertOrders(42L, 43L, 44L, 45L, 46L, 47L);

            // 4) DLQ 전송이 실패하면 원본을 지나가지 않고 멈춘다. 원인을 없애고 재시작하면 다시 보낸다.
            setDeadLetterMaxBytes(admin, "1024");
            assertThat(send(producer, "98", "not-json".getBytes(StandardCharsets.UTF_8)).offset()).isEqualTo(8);
            assertThat(sendOrder(producer, TOPIC, 48L).offset()).isEqualTo(9);
            await(() -> !container.isRunning());
            assertThat(committed(admin, GROUP, SOURCE)).isEqualTo(8L);
            assertThat(deadLetterOffsets()).containsExactly(1L, 2L);
            assertOrders(42L, 43L, 44L, 45L, 46L, 47L);

            setDeadLetterMaxBytes(admin, "1048576");
            container.start();
            await(() -> committed(admin, GROUP, SOURCE) == 10L);
            assertThat(deadLetterOffsets()).containsExactly(1L, 2L, 8L);
            assertOrders(42L, 43L, 44L, 45L, 46L, 47L, 48L);
        } finally {
            container.stop();
        }
    }

    private List<Duration> gaps(List<Long> times) {
        List<Duration> gaps = new ArrayList<>();
        for (int i = 1; i < times.size(); i++) {
            gaps.add(Duration.ofNanos(times.get(i) - times.get(i - 1)));
        }
        return gaps;
    }

    /** 중단할 때 consumer를 닫으며 생기는 revoke는 빼고, 재시도하는 동안 일어난 revoke만 센다. */
    private int revocationsBeforeStop(AtomicInteger revocations) {
        return Math.max(0, revocations.get() - 1);
    }

    private RecordMetadata send(KafkaProducer<String, byte[]> producer, String key, byte[] value) throws Exception {
        return producer.send(new ProducerRecord<>(TOPIC, key, value)).get(10, TimeUnit.SECONDS);
    }

    private byte[] payload(long orderId, int firstQuantity) {
        return """
            {"eventType":"OrderConfirmed","schemaVersion":1,"eventId":"order-confirmed:v1:%d","orderId":%d,
             "confirmedAt":"%s","items":[{"productId":7,"quantity":%d},{"productId":8,"quantity":3}]}
            """.formatted(orderId, orderId, CONFIRMED_AT, firstQuantity).getBytes(StandardCharsets.UTF_8);
    }

    private void setDeadLetterMaxBytes(AdminClient admin, String bytes) throws Exception {
        var resource = new ConfigResource(ConfigResource.Type.TOPIC, DLT);
        admin.incrementalAlterConfigs(Map.of(resource, List.of(new AlterConfigOp(
            new ConfigEntry("max.message.bytes", bytes), AlterConfigOp.OpType.SET)))).all().get(10, TimeUnit.SECONDS);
    }

    /** DLQ record의 원본 offset header를 순서대로 읽는다. */
    private List<Long> deadLetterOffsets() {
        var settings = new HashMap<>(consumerSettings());
        settings.put("group.id", "dlt-inspection-" + UUID.randomUUID());
        try (var consumer = new KafkaConsumer<String, byte[]>(settings)) {
            var partition = new TopicPartition(DLT, 0);
            consumer.assign(List.of(partition));
            consumer.seekToBeginning(List.of(partition));
            long end = consumer.endOffsets(List.of(partition)).get(partition);
            List<Long> offsets = new ArrayList<>();
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (consumer.position(partition) < end && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(200))) {
                    assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value(),
                        StandardCharsets.UTF_8)).isEqualTo(TOPIC);
                    assertThat(new String(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_CONSUMER_GROUP)
                        .value(), StandardCharsets.UTF_8)).isEqualTo(GROUP);
                    offsets.add(ByteBuffer.wrap(record.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_OFFSET)
                        .value()).getLong());
                }
            }
            return offsets;
        }
    }
}
