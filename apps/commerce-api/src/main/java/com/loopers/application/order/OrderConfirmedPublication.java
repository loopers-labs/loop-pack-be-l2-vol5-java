package com.loopers.application.order;

import com.loopers.application.order.OrderConfirmedUnpublished.Reason;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 주문 확정 이벤트의 발행 시작(docs/week6/design.md 3번). "commit이 성공하면, 고객 응답과 떼어서 보낸다"를 맡는다.
 *
 * - 등록은 afterCommit에 한다(docs/week6/decisions.md D-2). commit 성공이 실행 조건이라 롤백된 확정의 이벤트(거짓 이벤트)가
 *   나갈 경로가 없다(D-1, INV-601). 트랜잭션이 없으면 commit을 보장할 수 없으므로 등록을 거절한다.
 * - afterCommit은 고객 요청 스레드에서 돈다. 여기서는 발행 스레드에 넘기기만 하고 돌아온다 — send()가 metadata를 기다리며
 *   스레드를 붙잡거나, 예외가 호출자에게 올라가 확정된 주문에 500이 나가지 않게 한다(D-3, D-17, INV-603).
 * - 발행 스레드는 1개, 큐는 유한하다. 큐가 차면 orderId·eventId를 "미발행"으로 경고 로그에 남기고 버린다(D-23).
 *   CallerRuns는 요청 스레드가 send()를 하게 되고, Abort는 afterCommit에서 예외가 나며, Discard는 흔적이 없다.
 * - 정상 종료에서는 남은 작업을 끝낼 때까지 기다린다(D-4). 누락 자체는 이번 주에 막지 않는다 — 다시 채우는 일은 7주차 Outbox다.
 * - 모든 미발행(넘기기 실패·큐 가득 참·종료 중·종료 대기 초과·발행 예외)은 {@link OrderConfirmedUnpublished}로 기록하고
 *   Counter로 센다(D-26).
 */
@Component
public class OrderConfirmedPublication implements DisposableBean {

    private final OrderConfirmedPublisher publisher;
    private final OrderConfirmedUnpublished unpublished;
    private final Duration shutdownAwait;

    /**
     * executor는 별도 빈으로 두지 않고 이 빈이 직접 만들고 닫는다. 이 빈은 publisher(→ 전용 KafkaTemplate → ProducerFactory)에
     * 의존하므로, Spring은 종료 때 이 빈의 destroy()를 producer close보다 먼저 부른다. 그래서 "남은 작업을 끝내고 → producer가
     * flush·close" 순서가 보장된다(D-23). executor를 독립 빈으로 빼면 이 순서가 의존 관계로 드러나지 않는다.
     */
    private final ThreadPoolExecutor executor;

    @Autowired
    public OrderConfirmedPublication(
        OrderConfirmedPublisher publisher,
        OrderConfirmedUnpublished unpublished,
        @Value("${events.order-confirmed.publish.threads:1}") int threads,
        @Value("${events.order-confirmed.publish.queue-capacity:1000}") int queueCapacity,
        @Value("${events.order-confirmed.publish.shutdown-await:10s}") Duration shutdownAwait
    ) {
        this.publisher = publisher;
        this.unpublished = unpublished;
        this.shutdownAwait = shutdownAwait;
        this.executor = new ThreadPoolExecutor(threads, threads, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(queueCapacity), new PublishThreadFactory(), new RecordAndDrop());
    }

    /**
     * 확정 트랜잭션 안에서 부른다. commit이 성공하면 발행 스레드에 넘긴다. 롤백이면 아무 일도 없다.
     *
     * @throws IllegalStateException 실제 트랜잭션이 없을 때 — commit을 보장할 수 없는 발행은 시작하지 않는다
     */
    public void register(OrderConfirmedPayload payload) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()
            || !TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("[orderId = " + payload.orderId() + "] 트랜잭션 밖에서는 확정 이벤트 발행을 등록할 수 없습니다.");
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                handOff(payload);
            }
        });
    }

    /** 넘기기만 한다. 어떤 경우에도 호출자(고객 요청 스레드)에게 예외를 올리지 않는다. */
    private void handOff(OrderConfirmedPayload payload) {
        try {
            executor.execute(new PublishTask(payload));
        } catch (RuntimeException e) {
            unpublished.record(Reason.HAND_OFF_FAILED, payload.orderId(), payload.eventId(), e);
        }
    }

    /**
     * 정상 종료: 새 작업을 받지 않고, 남은 작업을 끝낼 때까지 기다린다. 대기 상한은 producer의 max.block 이상으로 둔다 —
     * 큐 앞의 send() 하나가 metadata를 기다리는 동안 끊지 않기 위해서다. 그래도 남으면 미발행으로 기록한다.
     */
    @Override
    public void destroy() throws InterruptedException {
        executor.shutdown();
        if (executor.awaitTermination(shutdownAwait.toMillis(), TimeUnit.MILLISECONDS)) {
            return;
        }
        List<Runnable> remaining = executor.shutdownNow();
        for (Runnable task : remaining) {
            if (task instanceof PublishTask publishTask) {
                record(Reason.SHUTDOWN_TIMEOUT, publishTask.payload(), null);
            }
        }
    }

    private void record(Reason reason, OrderConfirmedPayload payload, Throwable cause) {
        unpublished.record(reason, payload.orderId(), payload.eventId(), cause);
    }

    private final class PublishTask implements Runnable {

        private final OrderConfirmedPayload payload;

        private PublishTask(OrderConfirmedPayload payload) {
            this.payload = payload;
        }

        OrderConfirmedPayload payload() {
            return payload;
        }

        @Override
        public void run() {
            try {
                publisher.publish(payload);
            } catch (RuntimeException e) {
                // 발행 스레드가 죽지 않게 여기서 끝낸다. orderId만 있으면 주문 DB에서 payload를 다시 만들 수 있다(D-3).
                record(Reason.PUBLISH_FAILED, payload, e);
            }
        }
    }

    /** 큐가 차면 기록하고 버린다(D-23). 종료 중에 들어온 작업도 같은 길로 온다. */
    private final class RecordAndDrop implements RejectedExecutionHandler {

        @Override
        public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
            if (task instanceof PublishTask publishTask) {
                record(executor.isShutdown() ? Reason.SHUTTING_DOWN : Reason.QUEUE_FULL, publishTask.payload(), null);
            }
        }
    }

    private static final class PublishThreadFactory implements ThreadFactory {

        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "order-confirmed-publisher-" + sequence.incrementAndGet());
            // 종료 대기는 destroy()가 맡는다. JVM 종료를 이 스레드가 붙잡지 않게 한다.
            thread.setDaemon(true);
            return thread;
        }
    }
}
