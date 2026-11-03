package com.loopers.domains.order.out;

import com.loopers.domains.order.app.port.SendOutcome;
import java.util.concurrent.ExecutionException;
import java.util.stream.Stream;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.errors.DisconnectException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.NetworkException;
import org.apache.kafka.common.errors.NotEnoughReplicasException;
import org.apache.kafka.common.errors.NotLeaderOrFollowerException;
import org.apache.kafka.common.errors.RecordBatchTooLargeException;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.SerializationException;
import org.apache.kafka.common.errors.TimeoutException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.kafka.core.KafkaProducerException;

import static org.assertj.core.api.Assertions.assertThat;

// R9: 분류 기준은 예외 이름이 아니라 영향 범위다(relay-1 ①). 모든 행에 해당하는 실패는 broker 수준, 그 행 내용 때문인 실패만 행 수준이다.
// 실제 broker가 이 예외들을 내는 조건은 이 테스트가 증명하지 못한다(R11·3단계 실험이 일부를 본다).
class KafkaOrderConfirmedMessageSenderClassifyTest {

    static Stream<Arguments> failures() {
        return Stream.of(
            Arguments.of("delivery timeout", new ExecutionException(new TimeoutException("Expiring 1 record(s)")), true),
            // kafka-clients 3.8.1 KafkaProducer는 max.block 초과를 동기로 던지지 않고 failed future로 돌려준다. Spring은 이를 KafkaProducerException으로 감싼다.
            Arguments.of("max.block metadata 대기 초과(failed future)", new ExecutionException(new KafkaProducerException(
                new ProducerRecord<String, String>("order-events", "1", "{}"), "Failed to send",
                new TimeoutException("Topic order-events not present in metadata after 2000 ms."))), true),
            Arguments.of("가정: max.block TimeoutException을 동기로 던짐",
                new TimeoutException("Topic order-events not present in metadata after 2000 ms."), true),
            Arguments.of("가정: Spring이 감싼 TimeoutException을 동기로 던짐",
                new org.springframework.kafka.KafkaException("Send failed", new TimeoutException("not present in metadata")), true),
            Arguments.of("복제본 부족", new ExecutionException(new NotEnoughReplicasException("not enough replicas")), true),
            Arguments.of("연결 끊김", new ExecutionException(new DisconnectException("disconnected")), true),
            Arguments.of("네트워크", new ExecutionException(new NetworkException("network")), true),
            Arguments.of("리더 아님", new ExecutionException(new NotLeaderOrFollowerException("not leader")), true),
            Arguments.of("없는 topic", new ExecutionException(new UnknownTopicOrPartitionException("unknown topic")), true),
            Arguments.of("relay 로컬 대기 초과", new java.util.concurrent.TimeoutException("35000 ms"), true),
            Arguments.of("KafkaException 안의 복제본 부족", new ExecutionException(new KafkaException(new NotEnoughReplicasException("x"))), true),
            Arguments.of("메시지 너무 큼", new ExecutionException(new RecordTooLargeException("too large")), false),
            Arguments.of("묶음 너무 큼", new ExecutionException(new RecordBatchTooLargeException("batch too large")), false),
            Arguments.of("잘못된 topic 이름", new ExecutionException(new InvalidTopicException("invalid topic")), false),
            Arguments.of("직렬화", new SerializationException("cannot serialize"), false),
            Arguments.of("그 밖의 KafkaException", new ExecutionException(new KafkaException("unknown")), false));
    }

    @ParameterizedTest(name = "{0} → broker 수준={2}")
    @MethodSource("failures")
    @DisplayName("Kafka 예외는 원인 사슬에서 찾아 모든 행에 해당하면 broker 수준, 그 행 때문이면 행 수준으로 분류하고 원인 예외 이름과 메시지를 남긴다")
    void classifiesByImpactScope(String scene, Throwable failure, boolean brokerLevel) {
        SendOutcome outcome = KafkaOrderConfirmedMessageSender.classify(failure);

        if (brokerLevel) {
            assertThat(outcome).isInstanceOf(SendOutcome.BrokerFailure.class);
            assertThat(((SendOutcome.BrokerFailure) outcome).error())
                .contains(rootOf(failure).getClass().getSimpleName()).contains(rootOf(failure).getMessage());
        } else {
            assertThat(outcome).isInstanceOf(SendOutcome.RowFailure.class);
            assertThat(((SendOutcome.RowFailure) outcome).error())
                .contains(rootOf(failure).getClass().getSimpleName()).contains(rootOf(failure).getMessage());
        }
    }

    private static Throwable rootOf(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
