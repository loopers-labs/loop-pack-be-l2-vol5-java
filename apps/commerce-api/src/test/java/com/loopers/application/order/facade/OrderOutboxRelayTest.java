package com.loopers.application.order.facade;

import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.entity.OrderConfirmedOutbox;
import com.loopers.domain.order.enums.OrderOutboxStatus;
import com.loopers.domain.order.enums.OrderStatus;
import com.loopers.domain.order.gateway.OrderConfirmedSender;
import com.loopers.domain.order.vo.OrderConfirmedSendResult;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.kafka.OrderEventsTopicReader;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;

/**
 * 7주차 설계 7 의 relay 장면을 실제 MySQL · broker 로 확인함. topic 은 다른 테스트도 쓰므로 offset 으로 구간을 나눠 읽음.
 * 중단은 그 단계를 끝낸 뒤 다음 단계에서 예외로 표현하고, 재시작은 relay 를 새로 만들되 같은 DB · broker 를 씀
 */
@SpringBootTest
class OrderOutboxRelayTest {

    private static final Long USER_ID = 1L;

    @Autowired
    private OrderOutboxRelay relay;

    @MockitoSpyBean
    private OrderOutboxRelaySteps steps;

    @MockitoSpyBean
    private OrderConfirmedSender sender;

    @Autowired
    private OrderOutboxRelayProperties properties;

    @Autowired
    private Clock clock;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @Autowired
    private OrderEventsTopicReader topicReader;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private Brand brand;

    @BeforeEach
    void setUp() {
        brand = transactionTemplate.execute(status -> {
            Brand saved = new Brand("브랜드", null);
            entityManager.persist(saved);
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    @DisplayName("commit 뒤 relay 가 돌면, 저장된 key · payload 그대로 record 하나를 보내고 PUBLISHED 로 저장한다.")
    @Test
    void publishesStoredPayload() {
        // arrange
        Order order = confirmedOrder();
        OrderConfirmedOutbox pending = outboxOf(order);
        Map<TopicPartition, Long> before = topicReader.endOffsets();

        // act
        boolean ran = relay.runOnce();

        // assert
        List<ConsumerRecord<String, String>> records = topicReader.recordsSince(before);
        OrderConfirmedOutbox published = outboxOf(order);
        assertAll(
            () -> assertThat(ran).isTrue(),
            () -> assertThat(pending.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(records).singleElement().satisfies(record -> {
                assertThat(record.key()).isEqualTo(String.valueOf(order.getId()));
                assertThat(record.value()).isEqualTo(pending.getPayload());
            }),
            () -> assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED),
            () -> assertThat(published.getAttemptCount()).isEqualTo(1),
            () -> assertThat(published.getPublishedAt()).isNotNull()
        );
    }

    @DisplayName("broker 확인 뒤 PUBLISHED 저장 전에 멈추면 행은 PENDING 으로 남고, lease 뒤 새 relay 가 같은 eventId 를 별도 record 로 다시 보낸다. (D-105)")
    @Test
    void republishesSameEvent_whenStoppedAfterAck() {
        // arrange
        Order order = confirmedOrder();
        Map<TopicPartition, Long> before = topicReader.endOffsets();
        doThrow(new IllegalStateException("확인 뒤 저장 전 중단")).doCallRealMethod().when(steps).markPublished(any(), any());

        // act
        assertThrows(IllegalStateException.class, () -> relay.runOnce());
        OrderConfirmedOutbox stopped = outboxOf(order);
        restartedRelayAfterLease().runOnce();

        // assert
        List<ConsumerRecord<String, String>> records = topicReader.recordsSince(before);
        OrderConfirmedOutbox published = outboxOf(order);
        assertAll(
            () -> assertThat(stopped.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(stopped.getAttemptCount()).isEqualTo(1),
            () -> assertThat(records).hasSize(2),
            () -> assertThat(records).extracting(ConsumerRecord::value).containsOnly(published.getPayload()),
            () -> assertThat(records.get(0).offset()).isNotEqualTo(records.get(1).offset()),
            () -> assertThat(published.getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED),
            () -> assertThat(published.getAttemptCount()).isEqualTo(2)
        );
    }

    @DisplayName("send 도중 멈추면 PUBLISHED 를 미리 저장하지 않았으므로 PENDING 으로 남아, lease 뒤 새 relay 가 찾아 보낸다. (설계 7 반례)")
    @Test
    void findsAgain_whenStoppedDuringSend() {
        // arrange
        Order order = confirmedOrder();
        Map<TopicPartition, Long> before = topicReader.endOffsets();
        doThrow(new IllegalStateException("send 도중 중단")).doCallRealMethod().when(sender).send(anyString(), anyString());

        // act
        assertThrows(IllegalStateException.class, () -> relay.runOnce());
        OrderConfirmedOutbox stopped = outboxOf(order);
        boolean ranWithinLease = relay.runOnce();
        long recordsWithinLease = topicReader.recordsSince(before).size();
        restartedRelayAfterLease().runOnce();

        // assert
        assertAll(
            () -> assertThat(stopped.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(stopped.getNextAttemptAt()).isAfter(ZonedDateTime.now(clock)),
            () -> assertThat(ranWithinLease).isTrue(),
            () -> assertThat(recordsWithinLease).isZero(),
            () -> assertThat(topicReader.recordsSince(before)).hasSize(1),
            () -> assertThat(outboxOf(order).getStatus()).isEqualTo(OrderOutboxStatus.PUBLISHED)
        );
    }

    @DisplayName("전송이 일시적으로 실패하면 행은 PENDING 에 backoff 와 오류를 남기고, 확정된 주문은 그대로다.")
    @Test
    void keepsPendingAndOrder_whenTransientFailure() {
        // arrange
        Order order = confirmedOrder();
        doReturn(new OrderConfirmedSendResult.Transient("TimeoutException: broker 없음")).when(sender).send(anyString(), anyString());
        ZonedDateTime before = ZonedDateTime.now(clock);

        // act
        relay.runOnce();

        // assert
        OrderConfirmedOutbox outbox = outboxOf(order);
        assertAll(
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.PENDING),
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(1),
            () -> assertThat(outbox.getLastError()).isEqualTo("TimeoutException: broker 없음"),
            () -> assertThat(outbox.getNextAttemptAt()).isAfterOrEqualTo(before.plus(properties.backoffInitial())),
            () -> assertThat(outbox.getNextAttemptAt()).isBefore(before.plus(properties.lease())),
            () -> assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.CONFIRMED)
        );
    }

    @DisplayName("영구 오류면 BLOCKED 로 저장되고 다음 회차에서 다시 보내지 않는다.")
    @Test
    void blocks_whenPermanentFailure() {
        // arrange
        Order order = confirmedOrder();
        Map<TopicPartition, Long> before = topicReader.endOffsets();
        doReturn(new OrderConfirmedSendResult.Permanent("RecordTooLargeException: 너무 큼")).when(sender).send(anyString(), anyString());

        // act
        relay.runOnce();
        restartedRelayAfterLease().runOnce();

        // assert
        OrderConfirmedOutbox outbox = outboxOf(order);
        assertAll(
            () -> assertThat(outbox.getStatus()).isEqualTo(OrderOutboxStatus.BLOCKED),
            () -> assertThat(outbox.getAttemptCount()).isEqualTo(1),
            () -> assertThat(outbox.getLastError()).isEqualTo("RecordTooLargeException: 너무 큼"),
            () -> assertThat(topicReader.recordsSince(before)).isEmpty()
        );
    }

    @DisplayName("회차가 도는 동안 다른 호출이 오면 건너뛰어, 같은 행을 두 번 보내지 않는다. (D-103)")
    @Test
    void skipsRound_whenAnotherRoundRunning() throws Exception {
        // arrange
        Order order = confirmedOrder();
        Map<TopicPartition, Long> before = topicReader.endOffsets();
        CountDownLatch sending = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            sending.countDown();
            assertThat(release.await(10, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(sender).send(anyString(), anyString());
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            // act
            Future<Boolean> first = executor.submit(relay::runOnce);
            assertThat(sending.await(10, TimeUnit.SECONDS)).isTrue();
            boolean second = relay.runOnce();
            release.countDown();

            // assert
            assertAll(
                () -> assertThat(second).isFalse(),
                () -> assertThat(first.get(20, TimeUnit.SECONDS)).isTrue(),
                () -> assertThat(topicReader.recordsSince(before)).hasSize(1),
                () -> assertThat(outboxOf(order).getAttemptCount()).isEqualTo(1)
            );
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    /** 같은 DB · broker 를 쓰되 lease 가 지난 시각에서 새로 시작한 relay */
    private OrderOutboxRelay restartedRelayAfterLease() {
        Clock afterLease = Clock.offset(clock, properties.lease().plus(Duration.ofSeconds(1)));
        return new OrderOutboxRelay(steps, sender, afterLease, properties);
    }

    /** 0원 주문은 PG 없이 확정 TX 에서 Outbox 를 남김 */
    private Order confirmedOrder() {
        Product product = transactionTemplate.execute(status -> {
            Product saved = new Product(brand, "상품", 0L);
            saved.changeStock(10);
            entityManager.persist(saved);
            return saved;
        });
        Order order = transactionTemplate.execute(status -> {
            Order draft = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), 0L, 1)), ZonedDateTime.now());
            entityManager.persist(draft);
            return draft;
        });
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
        return order;
    }

    private OrderConfirmedOutbox outboxOf(Order order) {
        return transactionTemplate.execute(status -> entityManager
            .createQuery("select o from OrderConfirmedOutbox o where o.orderId = :orderId", OrderConfirmedOutbox.class)
            .setParameter("orderId", order.getId())
            .getSingleResult());
    }

    private Order reload(Order order) {
        return transactionTemplate.execute(status -> entityManager.find(Order.class, order.getId()));
    }
}
