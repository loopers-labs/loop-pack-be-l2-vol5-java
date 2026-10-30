package com.loopers.application.payment;

import com.loopers.application.order.OrderConfirmedEventListener;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEventPublisher;
import com.loopers.pgsimulator.PaymentStatus;
import com.loopers.testsupport.CommittedOrderObserver;
import com.loopers.testsupport.RecordingOrderEventPublisher;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertAll;

/**
 * 실제 HTTP 요청·로컬 PG fixture·결과 적용 TX 를 거친 최초 확정 뒤의 포트 호출을 확인한다(P2 의 HTTP 경로).
 * 포트는 기록용 fake 이므로 broker 기록 근거가 아니며, 관찰은 포트 호출 시점에 다른 스레드·연결의 새 TX 로 한다.
 */
@Timeout(20)
@DisplayName("주문 확정·콜백·대사 HTTP 로 처음 확정하면 commit 뒤 발행 포트가 한 번 불리고 그때 다른 연결에서 확정이 보인다.")
class OrderConfirmedPublicationHttpIntegrationTest extends PaymentHttpTestSupport {

    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private EntityManager entityManager;
    @Autowired private ApplicationContext applicationContext;
    @Autowired private OrderConfirmedEventListener listener;

    private CommittedOrderObserver observer;
    private final AtomicLong originalConnection = new AtomicLong(-1);

    @BeforeEach
    void observePublication() {
        observer = new CommittedOrderObserver(transactionManager, entityManager);
        recordedOrderEvents().onPublish(observer::observe);
        probe.on(PaymentTransactionProbe.Point.BEFORE_COMMIT, "applyObservation", order,
            () -> originalConnection.set(CommittedOrderObserver.currentConnectionId(entityManager)));
    }

    @AfterEach
    void closeObserver() throws InterruptedException {
        observer.close();
    }

    private void assertPublishedOnceAfterCommit(ResponseEntity<?> response) {
        OrderConfirmed event = recordedOrderEvents().published().get(0);
        CommittedOrderObserver.Observation seen = observer.observations().get(0);
        assertAll(
            () -> assertThat(response.getStatusCode().value()).isEqualTo(200),
            () -> assertThat(detail(order).path("status").asText()).isEqualTo("CONFIRMED"),
            () -> assertThat(recordedOrderEvents().published()).hasSize(1),
            () -> assertThat(event.orderId()).isEqualTo(order),
            () -> assertThat(event.items()).singleElement().satisfies(item -> {
                assertThat(item.productId()).isEqualTo(product.getId());
                assertThat(item.quantity()).isEqualTo(2L);
            }),
            () -> assertThat(observer.failures()).isEmpty(),
            () -> assertThat(seen.orderStatus()).isEqualTo("CONFIRMED"),
            () -> assertThat(seen.confirmedAt().toInstant()).isEqualTo(event.confirmedAt().toInstant()),
            () -> assertThat(originalConnection.get()).isPositive(),
            () -> assertThat(seen.connectionId()).isNotEqualTo(originalConnection.get()),
            () -> assertThat(seen.observerThread()).isNotEqualTo(seen.callerThread())
        );
    }

    private ResponseEntity<?> afterPending(Supplier<ResponseEntity<?>> apply) {
        assertThat(confirm(order).getStatusCode().value()).isEqualTo(202);
        awaitPgIdle();
        assertThat(recordedOrderEvents().published()).as("대기 중에는 포트 호출 없음").isEmpty();
        pg.setStatus(key(order), PaymentStatus.SUCCESS);
        return apply.get();
    }

    @DisplayName("이 컨텍스트의 발행 포트는 기록용 fake 하나이고, commit 뒤 listener 도 그 fake 를 부른다.")
    @Test
    void portIsSingleRecordingFake() {
        assertAll(
            () -> assertThat(applicationContext.getBeansOfType(OrderEventPublisher.class)).containsOnlyKeys("kafkaOrderEventPublisher"),
            () -> assertThat(recordedOrderEvents()).isInstanceOf(RecordingOrderEventPublisher.class),
            () -> assertThat(ReflectionTestUtils.getField(listener, "publisher")).isSameAs(recordedOrderEvents())
        );
    }

    @DisplayName("주문 확정 요청이 즉시 SUCCESS 를 받으면")
    @Test
    void immediateSuccess() {
        success(order);

        assertPublishedOnceAfterCommit(confirm(order));
    }

    @DisplayName("대기 뒤 콜백이 권위 조회로 SUCCESS 를 확인하면")
    @Test
    void callbackSuccess() {
        assertPublishedOnceAfterCommit(afterPending(() -> callback(order, key(order), "SUCCESS")));
    }

    @DisplayName("대기 뒤 대사가 SUCCESS 를 확인하면")
    @Test
    void reconcileSuccess() {
        assertPublishedOnceAfterCommit(afterPending(() -> reconcile(order)));
    }
}
