package com.loopers.application.outbox;

import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.domain.outbox.Outbox;
import com.loopers.domain.outbox.OutboxService;
import com.loopers.domain.outbox.OutboxStatus;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.kafka.TopicReader;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * relay 의 두 중단 지점에서 무엇이 남고, 다시 띄운 relay 가 어떻게 이어 가는지 실제 MySQL·Kafka 로 확인한다.
 * 중단은 예외로 만든 것이며 프로세스 강제 종료가 아니다. 재시작은 같은 DB·Kafka 에서 relay 를 새로 만드는 것으로 표현한다.
 */
class OutboxRelayInterruptionTest extends PaymentFlowTestSupport {

    private static final String TOPIC = "outbox-interruption-" + UUID.randomUUID();

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @DisplayName("확정 commit 뒤 relay 가 돌기 전에 멈춰도 Outbox 가 남아 있어, 다시 띄운 relay 가 그 사건을 발행한다.")
    @Test
    void publishesLater_whenStoppedAfterCommitBeforeRelay() throws IOException {
        // given: 러닝화 3,500 × 2 + 양말 2,000 × 1 = 9,000원 주문이 결제 시작(PENDING)까지 진행됐다.
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 5);
        Product socks = saveProduct("양말", 2_000, 5);
        Long orderId = orderFacade.create(user.getId(), List.of(
            new OrderItemCommand(shoes.getId(), 2),
            new OrderItemCommand(socks.getId(), 1)
        )).id();
        orderFacade.confirm(command(user, orderId, "attempt-1"));
        String eventId = "order-confirmed:v1:" + orderId;

        try (TopicReader reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC)) {
            // when 1: PG 성공을 확정 경로로 반영하고 relay 는 실행하지 않는다. (= 확정 commit 뒤 relay 전에 멈춘 상태)
            updateByStatusLookupResult(orderId, PgPaymentStatusLookupResult.answered(List.of(
                new PgTransactionInfo(pgKeyOf(orderId), providerOrderIdOf(orderId), 9_000, PgStatus.SUCCESS)
            )));

            // then 1: 주문은 확정됐고 Outbox 는 PENDING 이며, broker 에는 아직 아무것도 없다.
            assertThat(orderStatusOf(orderId)).isEqualTo("CONFIRMED");
            assertThat(jdbcTemplate.queryForObject("select status from outbox where event_id = ?", String.class, eventId))
                .isEqualTo("PENDING");
            assertThat(reader.recordsSinceOpen()).isZero();

            // when 2: relay 를 새로 만들어 한 회차를 실행한다.
            OutboxRelay restartedRelay = new OutboxRelay(
                outboxService, outboxPublisher, transactionManager, clock, 20, Duration.ofSeconds(30)
            );
            restartedRelay.relayOnce();

            // then 2: 저장된 사건이 broker 에 한 건 남고 Outbox 는 PUBLISHED 가 된다.
            List<ConsumerRecord<String, byte[]>> records = reader.awaitRecords(1, Duration.ofSeconds(10));
            assertThat(records).hasSize(1);
            assertThat(OrderConfirmed.getDecoder().decode(records.get(0).value()).getEventId()).isEqualTo(eventId);
            assertThat(jdbcTemplate.queryForObject("select status from outbox where event_id = ?", String.class, eventId))
                .isEqualTo("PUBLISHED");
        }
    }

    @DisplayName("broker 가 받은 뒤 PUBLISHED 를 저장하기 전에 멈추면 Outbox 는 PENDING 으로 남고, 다시 띄운 relay 가 같은 사건을 다른 offset 으로 한 번 더 발행한다.")
    @Test
    void republishesSameEvent_whenStoppedAfterBrokerAckBeforeMarkingPublished() {
        // given: 전송할 차례가 된 Outbox 한 건
        byte[] payload = {0x01, 0x02, 0x03};
        Outbox saved = outboxService.save(Outbox.record(
            "order-confirmed:v1:42", 42L, "OrderConfirmed", "v1", "42", payload, ZonedDateTime.now(clock).minusSeconds(1)
        ));
        // 실제 전송이 broker 확인을 받고 반환된 직후 멈춘다. relay 가 잡지 않는 예외라 PUBLISHED 저장까지 가지 못한다.
        OutboxPublisher stopsAfterBrokerAck = outbox -> {
            outboxPublisher.publish(outbox);
            throw new IllegalStateException("broker 확인 뒤 중단");
        };

        try (TopicReader reader = new TopicReader(KafkaTestContainersConfig.bootstrapServers(), TOPIC)) {
            // when 1
            OutboxRelay firstRelay = new OutboxRelay(
                outboxService, stopsAfterBrokerAck, transactionManager, clock, 20, Duration.ofSeconds(30)
            );
            assertThatThrownBy(firstRelay::relayOnce).hasMessage("broker 확인 뒤 중단");

            // then 1: broker 에는 한 건이 있지만 Outbox 는 여전히 PENDING 이다.
            assertThat(reader.recordsSinceOpen()).isEqualTo(1);
            Outbox afterStop = outboxService.get(saved.getId());
            assertThat(afterStop.getStatus()).isEqualTo(OutboxStatus.PENDING);
            assertThat(afterStop.getAttemptCount()).isEqualTo(1);

            // when 2: relay 를 새로 만들어 한 회차를 실행한다.
            OutboxRelay restartedRelay = new OutboxRelay(
                outboxService, outboxPublisher, transactionManager, clock, 20, Duration.ofSeconds(30)
            );
            restartedRelay.relayOnce();

            // then 2: 같은 key·payload 가 다른 offset 으로 한 번 더 남고 Outbox 는 PUBLISHED 가 된다.
            List<ConsumerRecord<String, byte[]>> records = reader.awaitRecords(2, Duration.ofSeconds(10));
            assertThat(records).hasSize(2);
            assertThat(records.get(0).key()).isEqualTo("42");
            assertThat(records.get(1).key()).isEqualTo("42");
            assertThat(records.get(0).value()).containsExactly(payload);
            assertThat(records.get(1).value()).containsExactly(payload);
            assertThat(records.get(1).offset()).isNotEqualTo(records.get(0).offset());
            Outbox afterRestart = outboxService.get(saved.getId());
            assertThat(afterRestart.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
            assertThat(afterRestart.getAttemptCount()).isEqualTo(2);
        }
    }
}
