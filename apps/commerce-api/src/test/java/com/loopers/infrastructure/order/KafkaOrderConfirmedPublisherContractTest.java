package com.loopers.infrastructure.order;

import com.loopers.application.order.OrderConfirmedSendException;
import com.loopers.application.order.SentRecord;
import com.loopers.testcontainers.KafkaTestContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 확인 지점 ② — Kafka에 실린 record의 key는 넘긴 message_key이고, value는 넘긴 payload와 바이트까지 같다
 * (docs/week7/decisions.md D-1, D-9, INV-702). 실제 broker(테스트 컨테이너)에 보내고 바이트로 다시 읽는다.
 *
 * <p>JSON 모양(계약 JSON과 같은지)은 7주차부터 OrderConfirmedJsonContractTest가 본다 — 전송은 JSON을 만들지 않는다.
 * producer 설정(StringSerializer·acks=all·max.block)은 OrderEventProducerConfigTest가 본다. 여기서는 같은 직렬화로 만든
 * template에 구현을 붙여 "받은 문자열을 바꾸지 않고 보내고, broker가 준 위치를 돌려주고, 실패는 예외로 올린다"만 본다.
 */
class KafkaOrderConfirmedPublisherContractTest {

    private static final String TOPIC = "order-events-contract-" + UUID.randomUUID();
    private static final Duration WAIT = Duration.ofSeconds(20);

    private static DefaultKafkaProducerFactory<String, String> producerFactory;
    private static KafkaOrderConfirmedPublisher publisher;

    @BeforeAll
    static void setUp() {
        KafkaTestContainers.createTopic(TOPIC);
        producerFactory = new DefaultKafkaProducerFactory<>(Map.of(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
            ProducerConfig.ACKS_CONFIG, "all"));
        publisher = new KafkaOrderConfirmedPublisher(new KafkaTemplate<>(producerFactory), TOPIC);
    }

    @AfterAll
    static void tearDown() {
        producerFactory.destroy();
    }

    /** 계약 파일을 글자 그대로 쓴다 — 줄바꿈·공백이 다시 만든 JSON과 다르므로, 중간에 다시 만들었다면 바이트가 달라진다. */
    private static String contractText() throws IOException {
        String dir = System.getProperty("contract.dir");
        assertThat(dir).as("contract.dir 시스템 프로퍼티(build.gradle.kts tasks.test)").isNotBlank();
        return Files.readString(Path.of(dir, "order-confirmed.v1.json"));
    }

    private static ConsumerRecord<byte[], byte[]> readAt(SentRecord sent) {
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false))) {
            TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
            consumer.assign(List.of(partition));
            consumer.seek(partition, sent.offset());
            List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (records.isEmpty() && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(200)).forEach(records::add);
            }
            assertThat(records).as("돌려받은 위치에서 record 읽기").isNotEmpty();
            return records.get(0);
        }
    }

    @DisplayName("key와 JSON 문자열을 보내면, 돌려받은 위치의 record는 key가 같고 value가 바이트까지 같다.")
    @Test
    void sendsKeyAndPayloadUnchanged() throws IOException {
        // arrange
        String payload = contractText();

        // act
        SentRecord sent = publisher.send("42", payload);

        // assert
        ConsumerRecord<byte[], byte[]> record = readAt(sent);
        System.out.printf("[OrderConfirmed 전송 계약] topic=%s, partition=%d, offset=%d%n",
            sent.topic(), sent.partition(), sent.offset());
        assertThat(sent.topic()).isEqualTo(TOPIC);
        assertThat(record.partition()).isEqualTo(sent.partition());
        assertThat(record.offset()).isEqualTo(sent.offset());
        assertThat(record.key()).isEqualTo("42".getBytes(StandardCharsets.UTF_8));
        assertThat(record.value()).isEqualTo(payload.getBytes(StandardCharsets.UTF_8));
    }

    @DisplayName("같은 key·payload를 다시 보내면 같은 partition의 새 offset에 같은 바이트가 실린다. (재발행은 새 record)")
    @Test
    void resendGoesToSamePartitionNewOffset() throws IOException {
        // arrange
        String payload = contractText();

        // act
        SentRecord first = publisher.send("43", payload);
        SentRecord second = publisher.send("43", payload);

        // assert
        assertThat(second.partition()).isEqualTo(first.partition());
        assertThat(second.offset()).isGreaterThan(first.offset());
        assertThat(readAt(second).value()).isEqualTo(readAt(first).value());
    }

    @DisplayName("broker 응답이 실패로 돌아오면 원인을 담은 OrderConfirmedSendException이 올라온다. 메시지에 payload는 없다.")
    @Test
    @SuppressWarnings("unchecked")
    void sendFailureSurfacesAsException() {
        // arrange
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        CompletableFuture<SendResult<String, String>> future = new CompletableFuture<>();
        IllegalStateException brokerFailure = new IllegalStateException("주입된 broker 실패");
        future.completeExceptionally(brokerFailure);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(future);

        // act
        OrderConfirmedSendException exception = assertThrows(OrderConfirmedSendException.class,
            () -> new KafkaOrderConfirmedPublisher(template, TOPIC).send("42", "{\"body\":\"payload-body\"}"));

        // assert
        assertThat(exception.getCause()).isSameAs(brokerFailure);
        assertThat(exception.getMessage()).contains("topic=" + TOPIC, "key=42").doesNotContain("payload-body");
    }

    @DisplayName("send() 자체가 던져도(예: max.block 초과) 같은 예외로 올라온다.")
    @Test
    @SuppressWarnings("unchecked")
    void synchronousFailureSurfacesAsException() {
        // arrange
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        RuntimeException blocked = new RuntimeException("metadata 대기 초과");
        when(template.send(anyString(), anyString(), anyString())).thenThrow(blocked);

        // act
        OrderConfirmedSendException exception = assertThrows(OrderConfirmedSendException.class,
            () -> new KafkaOrderConfirmedPublisher(template, TOPIC).send("42", "{}"));

        // assert
        assertThat(exception.getCause()).isSameAs(blocked);
    }

    @DisplayName("기다리는 중 인터럽트되면 인터럽트 표시를 되살리고 예외로 올라온다.")
    @Test
    @SuppressWarnings("unchecked")
    void interruptRestoresFlag() {
        // arrange
        KafkaTemplate<String, String> template = mock(KafkaTemplate.class);
        when(template.send(anyString(), anyString(), anyString())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt();

        try {
            // act
            assertThrows(OrderConfirmedSendException.class,
                () -> new KafkaOrderConfirmedPublisher(template, TOPIC).send("42", "{}"));

            // assert
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }
}
