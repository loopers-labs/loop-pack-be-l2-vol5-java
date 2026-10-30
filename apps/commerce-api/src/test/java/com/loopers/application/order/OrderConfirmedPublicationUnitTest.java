package com.loopers.application.order;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 발행 시작의 규칙만 본다(docs/week6/decisions.md D-2, D-3, D-23). DB·Kafka 없이, 트랜잭션은 아무것도 하지 않는
 * 트랜잭션 매니저로 commit·rollback 동기화만 실제 Spring 흐름대로 돌린다.
 */
class OrderConfirmedPublicationUnitTest {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final TransactionTemplate tx = new TransactionTemplate(new NoOpTransactionManager());
    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderConfirmedUnpublished.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final OrderConfirmedUnpublished unpublished = new OrderConfirmedUnpublished(meterRegistry);
    private OrderConfirmedPublication publication;

    @BeforeEach
    void setUp() {
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        logger.detachAppender(logs);
        if (publication != null) {
            publication.destroy();
        }
    }

    private static OrderConfirmedPayload payload(long orderId) {
        return new OrderConfirmedPayload(OrderConfirmedPayload.EVENT_TYPE, OrderConfirmedPayload.SCHEMA_VERSION,
            OrderConfirmedPayload.eventIdOf(orderId), orderId, ZonedDateTime.now(ZoneOffset.UTC),
            List.of(new OrderConfirmedPayload.Item(1L, 1)));
    }

    private double unpublishedCount(OrderConfirmedUnpublished.Reason reason) {
        return meterRegistry.get(OrderConfirmedUnpublished.METRIC)
            .tag(OrderConfirmedUnpublished.REASON_TAG, reason.tagValue())
            .counter().count();
    }

    private double unpublishedTotal() {
        return meterRegistry.get(OrderConfirmedUnpublished.METRIC).counters().stream().mapToDouble(c -> c.count()).sum();
    }

    private void registerAndCommit(OrderConfirmedPayload payload) {
        tx.executeWithoutResult(status -> publication.register(payload));
    }

    @DisplayName("commit되면 발행 스레드에서 publish가 한 번 불린다. 요청 스레드에서는 부르지 않는다.")
    @Test
    void publishesOnCommit_onAnotherThread() throws InterruptedException {
        // arrange
        RecordingPublisher publisher = new RecordingPublisher(1);
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 10, WAIT);

        // act
        registerAndCommit(payload(1L));

        // assert
        assertThat(publisher.done.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(publisher.orderIds).containsExactly(1L);
        assertThat(publisher.threads).doesNotContain(Thread.currentThread().getName());
    }

    @DisplayName("롤백되면 publish가 불리지 않는다. (commit 성공이 발행 조건 — INV-601)")
    @Test
    void doesNotPublish_onRollback() throws InterruptedException {
        // arrange
        RecordingPublisher publisher = new RecordingPublisher(1);
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 10, WAIT);

        // act
        tx.executeWithoutResult(status -> {
            publication.register(payload(1L));
            status.setRollbackOnly();
        });
        registerAndCommit(payload(2L)); // 같은 단일 스레드 큐 뒤에 넣어, 앞의 것이 있었다면 먼저 처리됐음을 보인다

        // assert
        assertThat(publisher.done.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(publisher.orderIds).containsExactly(2L);
    }

    @DisplayName("트랜잭션 밖에서 등록하면 IllegalStateException이다. commit을 보장할 수 없는 발행은 시작하지 않는다.")
    @Test
    void rejectsRegistration_withoutTransaction() {
        // arrange
        RecordingPublisher publisher = new RecordingPublisher(1);
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 10, WAIT);

        // act & assert
        assertThrows(IllegalStateException.class, () -> publication.register(payload(1L)));
        assertThat(publisher.orderIds).isEmpty();
    }

    @DisplayName("큐가 차면 commit은 예외 없이 끝나고, 넘친 이벤트는 orderId·eventId와 함께 미발행으로 기록하고 버린다.")
    @Test
    void dropsAndLogs_whenQueueIsFull() throws InterruptedException {
        // arrange — 스레드 1개는 첫 작업에 막혀 있고, 큐 2칸
        CountDownLatch release = new CountDownLatch(1);
        BlockingPublisher publisher = new BlockingPublisher(release, 3);
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 2, WAIT);
        registerAndCommit(payload(1L));
        assertThat(publisher.started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();

        // act — 2건은 큐로, 2건은 넘친다
        assertThatCode(() -> {
            for (long orderId = 2; orderId <= 5; orderId++) {
                registerAndCommit(payload(orderId));
            }
        }).doesNotThrowAnyException();

        // assert — 넘친 2건은 사유 queue_full로 센다(D-26). 다른 사유는 0이다.
        assertThat(unpublishedCount(OrderConfirmedUnpublished.Reason.QUEUE_FULL)).isEqualTo(2.0);
        assertThat(unpublishedTotal()).isEqualTo(2.0);
        assertThat(logs.list)
            .filteredOn(event -> event.getLevel() == Level.WARN)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anySatisfy(message -> assertThat(message).contains("미발행", "orderId=4", "eventId=order-confirmed:v1:4"))
            .anySatisfy(message -> assertThat(message).contains("미발행", "orderId=5", "eventId=order-confirmed:v1:5"));

        release.countDown();
        assertThat(publisher.done.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(publisher.orderIds).containsExactly(1L, 2L, 3L);
        assertThat(publisher.threads).doesNotContain(Thread.currentThread().getName());
    }

    @DisplayName("publish가 예외를 던져도 다음 이벤트는 계속 발행되고, 실패는 orderId·eventId와 함께 기록된다.")
    @Test
    void keepsPublishing_afterPublisherFailure() throws InterruptedException {
        // arrange
        RecordingPublisher publisher = new RecordingPublisher(2) {
            @Override
            public void publish(OrderConfirmedPayload payload) {
                super.publish(payload);
                if (payload.orderId() == 1L) {
                    throw new IllegalStateException("주입된 전송 실패");
                }
            }
        };
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 10, WAIT);

        // act
        registerAndCommit(payload(1L));
        registerAndCommit(payload(2L));

        // assert
        assertThat(publisher.done.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(publisher.orderIds).containsExactly(1L, 2L);
        assertThat(logs.list)
            .filteredOn(event -> event.getLevel() == Level.ERROR)
            .extracting(ILoggingEvent::getFormattedMessage)
            .anySatisfy(message -> assertThat(message).contains("미발행", "발행 예외", "orderId=1", "eventId=order-confirmed:v1:1"));
        assertThat(unpublishedCount(OrderConfirmedUnpublished.Reason.PUBLISH_FAILED)).isEqualTo(1.0);
    }

    @DisplayName("정상 종료(destroy)는 큐에 남은 작업을 끝낸 뒤에 돌아온다. (D-4)")
    @Test
    void drainsQueue_onShutdown() throws InterruptedException {
        // arrange — 작업마다 조금씩 걸리는 publisher
        RecordingPublisher publisher = new RecordingPublisher(5) {
            @Override
            public void publish(OrderConfirmedPayload payload) {
                try {
                    TimeUnit.MILLISECONDS.sleep(50);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                super.publish(payload);
            }
        };
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 10, WAIT);
        for (long orderId = 1; orderId <= 5; orderId++) {
            registerAndCommit(payload(orderId));
        }

        // act
        publication.destroy();
        publication = null;

        // assert
        assertThat(publisher.orderIds).containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(unpublishedTotal()).isZero();
    }

    @DisplayName("종료한 뒤 commit된 등록은 예외 없이 버려지고, 사유 shutting_down으로 센다.")
    @Test
    void dropsAndCounts_afterShutdown() throws InterruptedException {
        // arrange
        RecordingPublisher publisher = new RecordingPublisher(1);
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 10, WAIT);
        publication.destroy();

        // act
        assertThatCode(() -> registerAndCommit(payload(1L))).doesNotThrowAnyException();

        // assert
        assertThat(publisher.orderIds).isEmpty();
        assertThat(unpublishedCount(OrderConfirmedUnpublished.Reason.SHUTTING_DOWN)).isEqualTo(1.0);
    }

    @DisplayName("종료 대기 시간 안에 끝내지 못한 작업은 사유 shutdown_timeout으로 센다.")
    @Test
    void countsLeftovers_whenShutdownTimesOut() throws InterruptedException {
        // arrange — 첫 작업이 막혀 있고, 2건이 큐에 남은 채 짧게만 기다린다
        CountDownLatch release = new CountDownLatch(1);
        BlockingPublisher publisher = new BlockingPublisher(release, 1);
        publication = new OrderConfirmedPublication(publisher, unpublished, 1, 10, Duration.ofMillis(100));
        registerAndCommit(payload(1L));
        assertThat(publisher.started.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        registerAndCommit(payload(2L));
        registerAndCommit(payload(3L));

        // act
        publication.destroy();
        publication = null;

        // assert — 남은 2건은 미발행으로 센다. 막혀 있던 1건은 shutdownNow의 interrupt로 풀려 진행된다.
        release.countDown();
        assertThat(unpublishedCount(OrderConfirmedUnpublished.Reason.SHUTDOWN_TIMEOUT)).isEqualTo(2.0);
        assertThat(publisher.orderIds).doesNotContain(2L, 3L);
    }

    private static class RecordingPublisher implements OrderConfirmedPublisher {

        final List<Long> orderIds = new CopyOnWriteArrayList<>();
        final List<String> threads = new CopyOnWriteArrayList<>();
        final CountDownLatch done;

        RecordingPublisher(int expected) {
            this.done = new CountDownLatch(expected);
        }

        @Override
        public void publish(OrderConfirmedPayload payload) {
            orderIds.add(payload.orderId());
            threads.add(Thread.currentThread().getName());
            done.countDown();
        }
    }

    private static final class BlockingPublisher extends RecordingPublisher {

        final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch release;

        BlockingPublisher(CountDownLatch release, int expected) {
            super(expected);
            this.release = release;
        }

        @Override
        public void publish(OrderConfirmedPayload payload) {
            started.countDown();
            try {
                release.await(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            super.publish(payload);
        }
    }

    /** 자원 없이 commit·rollback 동기화만 실제 순서대로 돌리는 트랜잭션 매니저. */
    private static final class NoOpTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
            // 자원 없음
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
            // 자원 없음
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
            // 자원 없음
        }
    }
}
