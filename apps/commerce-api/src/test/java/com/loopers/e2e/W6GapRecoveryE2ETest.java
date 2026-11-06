package com.loopers.e2e;

import com.loopers.application.order.dto.OrderConfirmedInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.order.event.OrderConfirmedOutboxRecorder;
import com.loopers.application.outbox.OutboxPublisher;
import com.loopers.application.outbox.OutboxRelay;
import com.loopers.application.payment.PaymentFlowTestSupport;
import com.loopers.application.payment.PaymentStatusUpdater;
import com.loopers.application.payment.pg.PgPaymentStatusLookupResult;
import com.loopers.application.payment.pg.PgStatus;
import com.loopers.application.payment.pg.PgTransactionInfo;
import com.loopers.contract.order.OrderConfirmed;
import com.loopers.contract.order.OrderConfirmedItem;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStockService;
import com.loopers.domain.outbox.OutboxService;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.product.Product;
import com.loopers.domain.user.User;
import com.loopers.support.kafka.TopicReader;
import com.loopers.support.outbox.MissingOutboxRecovery;
import com.loopers.support.streamer.StreamerProcess;
import com.loopers.testcontainers.KafkaTestContainersConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W6 의 누락 상태를 새 격리 실행에서 재현하고 같은 실행 안에서 복구한다. 주문 A 는 확정 commit 뒤 발행 전에 멈췄고,
 * 주문 B 는 발행·소비까지 끝났다. 복구는 저장된 확정 사실로 없는 Outbox 를 채우고 relay·실제 streamer 로 흘려보낸다.
 * 역사적 원본 사건의 복원이 아니라 이번 실행에서 재현한 사건의 복구다.
 */
class W6GapRecoveryE2ETest extends PaymentFlowTestSupport {

    private static final String TOPIC = "w6-gap-recovery-" + UUID.randomUUID();

    @DynamicPropertySource
    static void topicProperty(DynamicPropertyRegistry registry) {
        registry.add("order-event.topic", () -> TOPIC);
    }

    @Autowired
    private OrderService orderService;

    @Autowired
    private PaymentAttemptService paymentAttemptService;

    @Autowired
    private OrderStockService orderStockService;

    @Autowired
    private OrderConfirmedOutboxRecorder recorder;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private OutboxPublisher outboxPublisher;

    @Autowired
    private KafkaTemplate<Object, Object> kafkaTemplate;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private Clock clock;

    @DisplayName("W6 에서 발행 전에 멈춘 주문 A 와 소비까지 끝난 주문 B 를 복구하면, A 의 구매 활동이 생기고 B 는 그대로다. PG·주문·재고는 늘지 않는다.")
    @Test
    void recoversMissingEvent_withoutChangingConsumedOne() throws Exception {
        // given: 이 테스트만 쓰는 topic·group 의 streamer, 러닝화 3,500원(재고 10)·양말 2,000원(재고 10)
        User user = saveUser(0);
        Product shoes = saveProduct("러닝화", 3_500, 10);
        Product socks = saveProduct("양말", 2_000, 10);
        String consumerGroup = "purchase-activity-" + UUID.randomUUID();
        TopicReader.createTopic(KafkaTestContainersConfig.bootstrapServers(), TOPIC);

        try (StreamerProcess streamer = StreamerProcess.start(TOPIC, consumerGroup)) {

            // 준비 1. W6 경로로 주문 A(러닝화×2, 양말×1)와 B(러닝화×1)를 확정한다. 확정 TX 는 그대로 실행하고 Outbox 만 남기지 않는다.
            Long orderA = orderFacade.create(user.getId(), List.of(
                new OrderItemCommand(shoes.getId(), 2), new OrderItemCommand(socks.getId(), 1)
            )).id();
            Long orderB = orderFacade.create(user.getId(), List.of(new OrderItemCommand(shoes.getId(), 1))).id();
            orderFacade.confirm(command(user, orderA, "attempt-a"));
            orderFacade.confirm(command(user, orderB, "attempt-b"));
            pg.setStatus(pgKeyOf(orderA), "SUCCESS");
            pg.setStatus(pgKeyOf(orderB), "SUCCESS");
            OrderConfirmedOutboxRecorder w6NoOutbox = new OrderConfirmedOutboxRecorder(null) {
                @Override
                public void record(Order order, ZonedDateTime recordedAt) {
                }
            };
            PaymentStatusUpdater w6Updater =
                new PaymentStatusUpdater(orderService, paymentAttemptService, orderStockService, w6NoOutbox, clock);
            TransactionTemplate transaction = new TransactionTemplate(transactionManager);
            transaction.execute(status -> w6Updater.updateByStatusLookupResult(orderA, PgPaymentStatusLookupResult.answered(List.of(
                new PgTransactionInfo(pgKeyOf(orderA), providerOrderIdOf(orderA), 9_000, PgStatus.SUCCESS)
            ))));
            transaction.execute(status -> w6Updater.updateByStatusLookupResult(orderB, PgPaymentStatusLookupResult.answered(List.of(
                new PgTransactionInfo(pgKeyOf(orderB), providerOrderIdOf(orderB), 3_500, PgStatus.SUCCESS)
            ))));

            assertThat(orderStatusOf(orderA)).isEqualTo("CONFIRMED");
            assertThat(orderStatusOf(orderB)).isEqualTo("CONFIRMED");
            assertThat(stockOf(shoes)).isEqualTo(7);
            assertThat(stockOf(socks)).isEqualTo(9);
            assertThat(jdbcTemplate.queryForObject("select count(*) from outbox", Long.class)).isZero();

            // 준비 2. A 는 아무것도 보내지 않는다(commit 뒤 발행 전에 멈춤). B 는 W6 직접 발행처럼 확정 사실을 바로 보내 소비까지 끝낸다.
            byte[] payloadB = transaction.execute(status -> {
                OrderConfirmedInfo facts = OrderConfirmedInfo.from(orderService.get(orderB));
                try {
                    ByteBuffer buffer = OrderConfirmed.newBuilder()
                        .setEventId(facts.eventId())
                        .setOrderId(facts.orderId())
                        .setConfirmedAt(facts.confirmedAt().toInstant())
                        .setItems(facts.items().stream().map(item -> new OrderConfirmedItem(item.productId(), item.quantity())).toList())
                        .build()
                        .toByteBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);
                    return bytes;
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            });
            kafkaTemplate.send(TOPIC, String.valueOf(orderB), payloadB).get(10, TimeUnit.SECONDS);

            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            long committedOffset = 0;
            while (committedOffset < 1 && System.nanoTime() < deadline) {
                Thread.sleep(200);
                committedOffset = TopicReader.committedOffset(KafkaTestContainersConfig.bootstrapServers(), TOPIC, consumerGroup);
            }
            assertThat(committedOffset).as(streamer.logTail()).isEqualTo(1);
            assertThat(activitiesOf(orderA)).isEmpty();
            List<String> activitiesOfBBeforeRecovery = activitiesOf(orderB);
            assertThat(activitiesOfBBeforeRecovery).containsExactly(shoes.getId() + "x1");

            // 준비 3. 이번 재현 실행의 사건과 PG 기준값을 기록한다.
            Instant confirmedAtA = orderService.get(orderA).getConfirmedAt().toInstant();
            Instant confirmedAtB = orderService.get(orderB).getConfirmedAt().toInstant();
            int pgCreateCalls = pg.createCalls();
            int pgTransactions = pg.transactionCount();
            int pgLookupCalls = pg.lookupByKeyCalls() + pg.lookupByOrderCalls();
            System.out.printf("재현 기록 A: orderId=%d, eventId=order-confirmed:v1:%d, confirmedAt=%s, items=[%dx2, %dx1], pgKey=%s%n",
                orderA, orderA, confirmedAtA, shoes.getId(), socks.getId(), pgKeyOf(orderA));
            System.out.printf("재현 기록 B: orderId=%d, eventId=order-confirmed:v1:%d, confirmedAt=%s, items=[%dx1], pgKey=%s%n",
                orderB, orderB, confirmedAtB, shoes.getId(), pgKeyOf(orderB));
            System.out.printf("재현 기록 PG: create 누적 C=%d, 외부 거래 수 T=%d%n", pgCreateCalls, pgTransactions);
            assertThat(pgCreateCalls).isEqualTo(2);
            assertThat(pgTransactions).isEqualTo(2);

            // 복구 4. 저장된 확정 사실로 없는 Outbox 를 채운다.
            MissingOutboxRecovery recovery = new MissingOutboxRecovery(orderService, recorder, jdbcTemplate, transactionManager, clock);
            recovery.recover(orderA);
            recovery.recover(orderB);

            assertThat(jdbcTemplate.queryForObject("select count(*) from outbox", Long.class)).isEqualTo(2L);
            byte[] outboxA = jdbcTemplate.queryForObject(
                "select payload from outbox where event_id = ?", byte[].class, "order-confirmed:v1:" + orderA
            );
            byte[] outboxB = jdbcTemplate.queryForObject(
                "select payload from outbox where event_id = ?", byte[].class, "order-confirmed:v1:" + orderB
            );
            assertThat(OrderConfirmed.getDecoder().decode(outboxA).getConfirmedAt()).isEqualTo(confirmedAtA);
            assertThat(OrderConfirmed.getDecoder().decode(outboxB).getConfirmedAt()).isEqualTo(confirmedAtB);

            // 복구 5. relay 로 보내고, streamer 가 A 와 B 재발행까지 처리할 때까지(commit offset 3) 기다린다.
            OutboxRelay relay = new OutboxRelay(outboxService, outboxPublisher, transactionManager, clock, 20, Duration.ofSeconds(30));
            relay.relayOnce();

            deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (committedOffset < 3 && System.nanoTime() < deadline) {
                Thread.sleep(200);
                committedOffset = TopicReader.committedOffset(KafkaTestContainersConfig.bootstrapServers(), TOPIC, consumerGroup);
            }
            assertThat(committedOffset).as(streamer.logTail()).isEqualTo(3);
            assertThat(activitiesOf(orderA)).containsExactly(shoes.getId() + "x2", socks.getId() + "x1");
            assertThat(activitiesOf(orderB)).isEqualTo(activitiesOfBBeforeRecovery);
            assertThat(jdbcTemplate.queryForObject(
                "select count(*) from processed_order_confirmation where order_id in (?, ?)", Long.class, orderA, orderB
            )).isEqualTo(2L);

            // 복구 6. 같은 대상을 한 번 더 복구해도 Outbox 는 늘지 않는다.
            recovery.recover(orderA);
            recovery.recover(orderB);
            assertThat(jdbcTemplate.queryForObject("select count(*) from outbox", Long.class)).isEqualTo(2L);

            // 최종: 복구 중 PG 추가 호출·주문 재확정·재고 추가 소비가 없다.
            assertThat(pg.createCalls()).isEqualTo(pgCreateCalls);
            assertThat(pg.transactionCount()).isEqualTo(pgTransactions);
            assertThat(pg.lookupByKeyCalls() + pg.lookupByOrderCalls()).isEqualTo(pgLookupCalls);
            assertThat(orderService.get(orderA).getConfirmedAt().toInstant()).isEqualTo(confirmedAtA);
            assertThat(orderService.get(orderB).getConfirmedAt().toInstant()).isEqualTo(confirmedAtB);
            assertThat(stockOf(shoes)).isEqualTo(7);
            assertThat(stockOf(socks)).isEqualTo(9);
        }
    }

    private List<String> activitiesOf(Long orderId) {
        return jdbcTemplate.query(
            "select product_id, quantity from product_purchase_activity where order_id = ? order by product_id",
            (rs, rowNum) -> rs.getLong("product_id") + "x" + rs.getInt("quantity"),
            orderId
        );
    }
}
