package com.loopers.interfaces.api.order;

import com.loopers.application.order.OrderConfirmedPayload;
import com.loopers.infrastructure.order.OrderConfirmedMessageSerializer;
import com.loopers.infrastructure.payment.TestPgServer.CreateBehavior;
import com.loopers.interfaces.consumer.RepublishRequestsListener;
import com.loopers.support.kafka.OrderEventsProbe;
import com.loopers.support.kafka.OrderEventsTestConfig.FailingKafkaTemplate;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;

import static com.loopers.kafka.test.KafkaTestSupport.admin;
import static com.loopers.kafka.test.KafkaTestSupport.awaitUntil;
import static com.loopers.kafka.test.KafkaTestSupport.committedOffset;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// 재발행 명령을 topic에 직접 넣고 실제 listener 컨테이너 → 주문 재확인 → OrderConfirmed send → 명령 ack를 본다.
// 명령은 listener 배정이 끝난 뒤 보낸다(offset reset 설정에 기대지 않음).
@ExtendWith(OutputCaptureExtension.class)
class OrderEventRepublishIntegrationTest extends OrderEventsIntegrationSupport {
    private static final TopicPartition REQUESTS = new TopicPartition(REQUEST_TOPIC, 0);
    private static final String INVALID_METRIC = "commerce.order.events.republish.invalid";

    @Autowired private KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
    @Autowired private MeterRegistry meterRegistry;

    private Admin admin;
    private KafkaProducer<String, String> requests;
    private MessageListenerContainer container;

    @BeforeEach
    void prepareRequests() {
        admin = admin(bootstrapServers);
        requests = new KafkaProducer<>(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
        container = kafkaListenerEndpointRegistry.getListenerContainer(RepublishRequestsListener.LISTENER_ID);
        awaitAssigned();
    }

    @AfterEach
    void closeRequests() {
        failingKafka.fail(FailingKafkaTemplate.Failure.NONE);
        if (!container.isRunning()) {
            container.start();
        }
        requests.close();
        admin.close();
    }

    @Test
    @DisplayName("CONFIRMED 주문의 재발행 명령을 처리하면 OrderConfirmed가 1건 더 나가고 처음 발행과 key·eventId·정규화한 내용이 같으며 명령 offset n+1이 commit된다")
    void republishesSameEventAsFirstPublish() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        ConsumerRecord<String, String> first = probe.awaitEvent(from, eventId(orderId));

        long n = request(orderId);

        awaitCommitted(n + 1);
        List<ConsumerRecord<String, String>> published = probe.awaitEvents(from, eventId(orderId), 2);
        ConsumerRecord<String, String> republished = published.get(1);
        assertThat(republished.key()).isEqualTo(first.key());
        assertThat(OrderEventsProbe.json(republished)).isEqualTo(OrderEventsProbe.json(first));
    }

    @Test
    @DisplayName("사례 A 복구(실험 ④): commit 뒤 send 직전에 멈춰 broker에 없던 확정을 대사 명령으로 재발행하면 처음 발행됐어야 할 payload 그대로 1건이 나간다")
    void caseAIsRecoveredByRepublishRequest() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        publisher.haltBeforeSend();
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        OrderConfirmedPayload halted = publisher.halted().getFirst();
        publisher.reset();

        long n = request(orderId);

        awaitCommitted(n + 1);
        ConsumerRecord<String, String> recovered = probe.awaitEvent(from, eventId(orderId));
        assertThat(recovered.key()).isEqualTo(halted.key());
        assertThat(OrderEventsProbe.json(recovered))
            .isEqualTo(OrderEventsProbe.json(OrderConfirmedMessageSerializer.serialize(halted)));
        assertThat(probe.awaitEvents(from, eventId(orderId), 1)).hasSize(1);
        System.out.println("[실험④ 3b 사례 A 복구·api] orderId=" + orderId + ", eventId=" + halted.eventId()
            + ", 명령 record=(topic=" + REQUEST_TOPIC + ", partition=0, offset=" + n + ") → committed=" + committed()
            + ", OrderConfirmed record=(topic=" + recovered.topic() + ", partition=" + recovered.partition() + ", offset="
            + recovered.offset() + ", key=" + recovered.key() + "), value=" + recovered.value());
    }

    @Test
    @DisplayName("없는 주문·결제 대기 주문·확정 시각 없는 옛 CONFIRMED 주문의 명령은 재발행하지 않고 ack한다")
    void skipsOrdersThatCannotBeRepublished() throws Exception {
        long pending = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_PENDING);
        confirm(pending, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        long legacy = newOrder();
        jdbc.update("update orders set status = 'CONFIRMED' where id = ?", legacy);

        request(999_999);
        request(pending);
        long last = request(legacy);

        awaitCommitted(last + 1);
        assertThat(eventIdsThroughBoundary()).doesNotContain(eventId(999_999), eventId(pending), eventId(legacy));
    }

    @Test
    @DisplayName("재발행 send가 계속 실패하면 재시도 뒤 컨테이너가 멈추고 명령 offset은 그대로이며, 실패를 풀고 재시작하면 재발행하고 n+1을 commit한다")
    void failedRepublishRetriesThenStopsAndResumes() throws Exception {
        long orderId = newOrder();
        server.createBehavior(CreateBehavior.ACCEPT_SUCCESS);
        publisher.haltBeforeSend();
        confirm(orderId, UUID.randomUUID().toString()).andExpect(status().isOk());
        publisher.reset();
        failingKafka.fail(FailingKafkaTemplate.Failure.ASYNC_FAILED);
        Long committedBefore = committed();

        long n = request(orderId);

        awaitUntil("재발행 명령 컨테이너 정지", () -> !container.isRunning());
        assertThat(committed()).isEqualTo(committedBefore);
        failingKafka.fail(FailingKafkaTemplate.Failure.NONE);
        container.start();
        awaitCommitted(n + 1);
        assertThat(probe.awaitEvents(from, eventId(orderId), 1)).hasSize(1);
    }

    @Test
    @DisplayName("모르는 명령 버전은 재시도 없이 바로 멈추고 명령 offset을 commit하지 않는다")
    void unknownVersionStopsImmediately() throws Exception {
        Long committedBefore = committed();
        Instant sentAt = Instant.now();

        long n = send("1", "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":2,\"orderId\":1,"
            + "\"requestedAt\":\"2026-10-29T01:10:00Z\"}");

        awaitUntil("재발행 명령 컨테이너 정지", () -> !container.isRunning());
        assertThat(Duration.between(sentAt, Instant.now())).isLessThan(Duration.ofSeconds(3));
        assertThat(committed()).isEqualTo(committedBefore);
        // 다음 테스트가 같은 topic을 쓰므로 정지 원인 record 다음으로 group 위치를 옮긴 뒤 재시작한다.
        // 멈춘 consumer가 group에서 빠지기 전에는 위치를 옮길 수 없어 성공할 때까지 다시 시도한다.
        awaitUntil("재발행 명령 group 위치를 n+1로 이동", () -> {
            try {
                admin.alterConsumerGroupOffsets(REQUEST_GROUP, Map.of(REQUESTS, new OffsetAndMetadata(n + 1)))
                    .all().get(10, TimeUnit.SECONDS);
                return true;
            } catch (Exception e) {
                return false;
            }
        });
    }

    @Test
    @DisplayName("깨진 명령은 ERROR 로그와 지표를 남기고 ack해 다음 명령으로 넘어간다")
    void brokenCommandIsLoggedAndAcked(CapturedOutput output) throws Exception {
        double before = invalidCommands();

        long n = send("42", "{\"commandType\":\"RepublishOrderConfirmed\",");

        awaitCommitted(n + 1);
        assertThat(invalidCommands()).isEqualTo(before + 1);
        assertThat(output.getAll()).contains("깨진 재발행 명령").contains("offset=" + n);
    }

    private long request(long orderId) throws Exception {
        return send(String.valueOf(orderId), "{\"commandType\":\"RepublishOrderConfirmed\",\"schemaVersion\":1,\"orderId\":"
            + orderId + ",\"requestedAt\":\"2026-10-29T01:10:00Z\"}");
    }

    private long send(String key, String json) throws Exception {
        return requests.send(new ProducerRecord<>(REQUEST_TOPIC, key, json)).get(10, TimeUnit.SECONDS).offset();
    }

    private Long committed() throws Exception {
        return committedOffset(admin, REQUEST_GROUP, REQUESTS);
    }

    private void awaitCommitted(long expected) {
        awaitUntil("재발행 명령 committed offset " + expected, () -> {
            try {
                return Long.valueOf(expected).equals(committed());
            } catch (Exception e) {
                throw new IllegalStateException("committed offset 조회 실패", e);
            }
        });
    }

    private void awaitAssigned() {
        awaitUntil("재발행 명령 listener 배정", () -> {
            Collection<TopicPartition> assigned = container.getAssignedPartitions();
            return container.isRunning() && assigned != null && !assigned.isEmpty();
        });
    }

    private double invalidCommands() {
        Counter counter = meterRegistry.find(INVALID_METRIC).counter();
        return counter == null ? 0 : counter.count();
    }
}
