package com.loopers.application.order.listener;

import com.loopers.application.order.facade.OrderPaymentFacade;
import com.loopers.application.order.facade.OrderPaymentFacade.ConfirmCommand;
import com.loopers.domain.brand.entity.Brand;
import com.loopers.domain.order.entity.Order;
import com.loopers.domain.order.gateway.OrderEventPublisher;
import com.loopers.domain.order.vo.OrderConfirmed;
import com.loopers.domain.order.vo.OrderLine;
import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.product.entity.Product;
import com.loopers.support.kafka.OrderEventsTopicReader;
import com.loopers.support.pg.TestPgServer;
import com.loopers.utils.DatabaseCleanUp;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;

/**
 * 6주차 설계 7.2 의 commit 뒤 발행 누락 구간. 실제 확정 · PG fixture · 결과 적용 경로로 두 주문을 확정하고,
 * 첫 주문만 commit 뒤 send 직전(발행 포트)에서 멈춤. OS 강제 종료가 아니라 통제된 hook 이며, DB 와 broker 가 원자적이지 않다는 관찰임
 */
@SpringBootTest
class OrderConfirmedPublicationGapTest {

    private static final Logger log = LoggerFactory.getLogger(OrderConfirmedPublicationGapTest.class);
    private static final Long USER_ID = 1L;

    @Autowired
    private OrderPaymentFacade orderPaymentFacade;

    @MockitoSpyBean
    private OrderEventPublisher orderEventPublisher;

    @Autowired
    private OrderEventsTopicReader topicReader;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private DatabaseCleanUp databaseCleanUp;

    private final TestPgServer pg = TestPgServer.shared();
    private Product product;

    @BeforeEach
    void setUp() {
        pg.reset();
        product = transactionTemplate.execute(status -> {
            Brand brand = new Brand("브랜드", null);
            entityManager.persist(brand);
            Product saved = new Product(brand, "상품", 1_000L);
            saved.changeStock(10);
            entityManager.persist(saved);
            return saved;
        });
    }

    @AfterEach
    void tearDown() {
        databaseCleanUp.truncateAllTables();
    }

    private Order confirmedByPg(int quantity) {
        Order order = transactionTemplate.execute(status -> {
            Order draft = Order.draft(USER_ID, List.of(new OrderLine(product.getId(), product.getName(), product.getPrice(), quantity)),
                ZonedDateTime.now());
            entityManager.persist(draft);
            return draft;
        });
        orderPaymentFacade.confirm(USER_ID, order.getId(), new ConfirmCommand("attempt-" + order.getId(), "TEST", "0000-0000-0000-0000"));
        pg.complete(transactionKeyOf(order), "SUCCESS");
        return order;
    }

    private String transactionKeyOf(Order order) {
        return pg.transactionsOf(Payment.providerOrderIdOf(order.getId())).get(0).transactionKey();
    }

    /** 확정 TX 와 별개의 커넥션으로 읽어 commit 된 값만 봄 */
    private Object[] stateOf(Long orderId) {
        EntityManager reader = entityManagerFactory.createEntityManager();
        try {
            return (Object[]) reader.createNativeQuery(
                "select o.status, p.reservation_status, o.confirmed_at from orders o join payment p on p.order_id = o.id where o.id = " + orderId)
                .getSingleResult();
        } finally {
            reader.close();
        }
    }

    @DisplayName("commit 뒤 send 직전에 멈추면, 주문 확정 · 예약 소비는 남지만 그 eventId 의 broker record 는 없다. 같은 경로의 다른 주문은 발행된다.")
    @Test
    void leavesConfirmedOrderWithoutRecord_whenStoppedBeforeSend() {
        // arrange
        Order missed = confirmedByPg(2);
        Order published = confirmedByPg(1);
        AtomicReference<Object[]> stateAtHook = new AtomicReference<>();
        AtomicReference<OrderConfirmed> stoppedEvent = new AtomicReference<>();
        // 첫 주문만 발행 포트에 들어온 순간(commit 뒤) 상태를 기록하고 실제 send 를 부르지 않음
        doAnswer(invocation -> {
            OrderConfirmed event = invocation.getArgument(0);
            stoppedEvent.set(event);
            stateAtHook.set(stateOf(event.orderId()));
            return null;
        }).when(orderEventPublisher).publish(argThat(event -> event != null && event.orderId().equals(missed.getId())));
        Map<TopicPartition, Long> before = topicReader.endOffsets();

        // act
        orderPaymentFacade.reconcile(USER_ID, missed.getId());
        orderPaymentFacade.reconcile(USER_ID, published.getId());

        // assert
        List<ConsumerRecord<String, String>> records = topicReader.recordsSince(before);
        Object[] missedState = stateOf(missed.getId());
        Object[] publishedState = stateOf(published.getId());
        log.info("[7.2 누락] orderId={} providerOrderId={} transactionKey={} eventId={} confirmedAt={} items={} hook 시점 상태={}/{} 최종 상태={}/{} broker record={}건",
            missed.getId(), Payment.providerOrderIdOf(missed.getId()), transactionKeyOf(missed), stoppedEvent.get().eventId(),
            stoppedEvent.get().confirmedAt(), stoppedEvent.get().items(), stateAtHook.get()[0], stateAtHook.get()[1],
            missedState[0], missedState[1], records.stream().filter(r -> r.key().equals(String.valueOf(missed.getId()))).count());
        records.stream().filter(r -> r.key().equals(String.valueOf(published.getId()))).forEach(r ->
            log.info("[7.2 정상] orderId={} providerOrderId={} transactionKey={} 상태={}/{} broker={}-{}@{} value={}",
                published.getId(), Payment.providerOrderIdOf(published.getId()), transactionKeyOf(published),
                publishedState[0], publishedState[1], r.topic(), r.partition(), r.offset(), r.value()));
        assertAll(
            () -> assertThat(stateAtHook.get()).as("hook 은 commit 뒤에 불림").containsExactly("CONFIRMED", "CONSUMED", stateAtHook.get()[2]),
            () -> assertThat(new Object[] {missedState[0], missedState[1]}).containsExactly("CONFIRMED", "CONSUMED"),
            () -> assertThat(records).noneMatch(r -> r.key().equals(String.valueOf(missed.getId()))),
            () -> assertThat(records).filteredOn(r -> r.key().equals(String.valueOf(published.getId()))).hasSize(1)
        );
    }
}
