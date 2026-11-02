package com.loopers.experiment;

import com.loopers.support.KafkaProbe;
import com.loopers.testcontainers.KafkaTestContainers;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.AcknowledgingMessageListener;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.KafkaMessageListenerContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실험 C 추가(과제 밖). 같은 {@code MANUAL_IMMEDIATE} listener에서 error handler만 바꿔, 계속 실패하는 기록 하나가
 * 어떻게 되는지 비교한다(docs/week6/decisions.md D-21, D-29).
 *
 * <p>Spring 기본값인 {@link DefaultErrorHandler}는 같은 기록을 총 10번(FixedBackOff(0, 9)) 시도한 뒤 로그만 남기고 다음
 * 기록으로 넘어간다. 다음 기록을 ack하면 그 offset + 1이 commit돼, 실패한 기록은 처리되지 않은 채 건너뛴다.
 * 과제가 지정한 {@link CommonContainerStoppingErrorHandler}는 한 번 실패하면 멈추고, committed offset은 실패한 기록에 머문다.
 *
 * <p>DB 없이 spring-kafka container를 코드로 띄운다. listener는 정해 둔 offset에서만 예외를 던지고 나머지는 ack한다.
 */
class ErrorHandlerSkipExperimentTest {

    private static final Duration WAIT = Duration.ofSeconds(30);
    private static final int PARTITION = 0;

    /** 실험 한 번의 관찰값. */
    private record Observation(long m, Map<Long, Integer> attempts, List<Long> acked, long committed, boolean running) {
        int attemptsOf(long offset) {
            return attempts.getOrDefault(offset, 0);
        }
    }

    private static Observation run(String name, CommonErrorHandler errorHandler, boolean expectStop) {
        String topic = KafkaProbe.unique("experiment-error-handler-" + name);
        String group = KafkaProbe.unique("experiment-error-handler-" + name + "-group");
        KafkaTestContainers.createTopic(topic);

        try (KafkaProbe kafka = new KafkaProbe(topic, group)) {
            // arrange — 한 partition에 3건(m, m+1, m+2). m+1만 계속 실패한다
            RecordMetadata first = kafka.send(PARTITION, "k-0", "v-0".getBytes(StandardCharsets.UTF_8));
            kafka.send(PARTITION, "k-1", "v-1".getBytes(StandardCharsets.UTF_8));
            kafka.send(PARTITION, "k-2", "v-2".getBytes(StandardCharsets.UTF_8));
            long m = first.offset();
            long failing = m + 1;

            Map<Long, Integer> attempts = new ConcurrentHashMap<>();
            List<Long> acked = new CopyOnWriteArrayList<>();

            ContainerProperties properties = new ContainerProperties(topic);
            properties.setGroupId(group);
            properties.setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
            properties.setMessageListener((AcknowledgingMessageListener<String, byte[]>) (record, ack) -> {
                attempts.merge(record.offset(), 1, Integer::sum);
                if (record.partition() == PARTITION && record.offset() == failing) {
                    throw new IllegalStateException("실험: offset " + failing + "은 항상 실패한다");
                }
                acked.add(record.offset());
                ack.acknowledge();
            });

            KafkaMessageListenerContainer<String, byte[]> container = new KafkaMessageListenerContainer<>(
                new DefaultKafkaConsumerFactory<String, byte[]>(Map.of(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KafkaTestContainers.bootstrapServers(),
                    ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                    ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class,
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")),
                properties);
            container.setCommonErrorHandler(errorHandler);

            // act — 기본 handler는 m+2의 ack로 commit이 m+3에 닿을 때까지, 멈춤 handler는 container가 멈출 때까지 기다린다
            container.start();
            try {
                if (expectStop) {
                    await().atMost(WAIT).until(() -> !container.isRunning());
                } else {
                    await().atMost(WAIT).until(() -> kafka.committed(PARTITION) == m + 3);
                }
                boolean running = container.isRunning();
                return new Observation(m, Map.copyOf(attempts), List.copyOf(acked), kafka.committed(PARTITION), running);
            } finally {
                container.stop();
            }
        }
    }

    private static void print(String tag, Observation o) {
        System.out.printf("[%s] 보낸 기록 m=%d, m+1=%d(항상 실패), m+2=%d%n", tag, o.m(), o.m() + 1, o.m() + 2);
        System.out.printf("[%s] m+1 시도 %d회, ack한 기록 %s, container running=%s, committed=%d%n",
            tag, o.attemptsOf(o.m() + 1), o.acked(), o.running(), o.committed());
    }

    @DisplayName("C 추가(과제 밖): DefaultErrorHandler(기본값)는 계속 실패하는 m+1을 10번 시도한 뒤 건너뛴다. "
        + "m+2를 ack하면 committed가 m+3이 돼 m+1은 처리되지 않은 채 지나간다.")
    @Test
    void defaultErrorHandlerSkipsFailingRecordAfterRetries() {
        Observation o = run("default", new DefaultErrorHandler(), false);

        print("C-extra default", o);
        assertAll(
            () -> assertThat(o.attemptsOf(o.m() + 1)).isEqualTo(10),
            () -> assertThat(o.acked()).containsExactly(o.m(), o.m() + 2),
            () -> assertThat(o.committed()).isEqualTo(o.m() + 3)
        );
    }

    @DisplayName("C 추가(과제 밖): CommonContainerStoppingErrorHandler(과제 지정)는 m+1에서 한 번 실패하면 멈춘다. "
        + "committed는 m+1에 머물고 m+2는 처리하지 않는다.")
    @Test
    void stoppingErrorHandlerStopsAtFailingRecord() {
        Observation o = run("stopping", new CommonContainerStoppingErrorHandler(), true);

        print("C-extra stopping", o);
        assertAll(
            () -> assertThat(o.attemptsOf(o.m() + 1)).isEqualTo(1),
            () -> assertThat(o.acked()).containsExactly(o.m()),
            () -> assertThat(o.running()).isFalse(),
            () -> assertThat(o.committed()).isEqualTo(o.m() + 1)
        );
    }
}
