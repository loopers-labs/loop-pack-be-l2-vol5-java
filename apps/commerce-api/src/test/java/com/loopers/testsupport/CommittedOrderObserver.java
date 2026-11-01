package com.loopers.testsupport;

import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderModel;
import jakarta.persistence.EntityManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * 발행 포트가 불린 그 시점에 원 TX 의 commit 결과가 다른 연결에서 보이는지 관찰한다(P2).
 * 같은 스레드의 조회나 TX 활성 플래그는 원 TX 의 연결·영속성 컨텍스트를 다시 볼 수 있어 commit 근거가 되지 않으므로,
 * 별도 스레드에서 새 readOnly TX·새 영속성 컨텍스트를 열어 MySQL 연결 ID 와 함께 읽는다.
 *
 * <p>AFTER_COMMIT 호출자는 원 연결을 쥔 채 기다리므로 관찰은 스레드 하나로 한 번에 하나만 하고, 테스트 풀(최대 10)에 여유를 남긴다.
 * 관찰 결과와 실패는 모두 기록해 테스트가 직접 확인한다. listener 의 오류 로그에 기대지 않는다.
 */
public class CommittedOrderObserver implements AutoCloseable {

    private static final Duration WAIT = Duration.ofSeconds(5);

    private final TransactionTemplate readOnly;
    private final EntityManager entityManager;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "committed-order-observer");
        thread.setDaemon(true);
        return thread;
    });
    private final List<Observation> observations = new CopyOnWriteArrayList<>();
    private final List<Throwable> failures = new CopyOnWriteArrayList<>();

    public CommittedOrderObserver(PlatformTransactionManager transactionManager, EntityManager entityManager) {
        this.readOnly = new TransactionTemplate(transactionManager);
        this.readOnly.setReadOnly(true);
        this.entityManager = entityManager;
    }

    /** 포트 호출 시점의 관찰. orderStatus·confirmedAt 은 새 영속성 컨텍스트, connectionId 는 그 TX 의 MySQL 연결이다. */
    public record Observation(OrderConfirmed event, String callerThread, String observerThread, boolean observerTxActive,
                              long connectionId, String orderStatus, ZonedDateTime confirmedAt) {
    }

    /** 기록용 포트의 hook 으로 넘긴다. 관찰이 실패해도 예외를 던지지 않고 기록만 해, 포트 외곽 오류와 섞이지 않게 한다. */
    public void observe(OrderConfirmed event) {
        String caller = Thread.currentThread().getName();
        try {
            observations.add(worker.submit(() -> readOnly.execute(status -> {
                long connectionId = ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult())
                    .longValue();
                OrderModel order = entityManager.find(OrderModel.class, event.orderId());
                return new Observation(event, caller, Thread.currentThread().getName(),
                    TransactionSynchronizationManager.isActualTransactionActive(), connectionId,
                    order == null ? null : order.getStatus().name(), order == null ? null : order.getConfirmedAt());
            })).get(WAIT.toSeconds(), TimeUnit.SECONDS));
        } catch (Exception | AssertionError failure) {
            failures.add(failure);
        }
    }

    public List<Observation> observations() {
        return List.copyOf(observations);
    }

    public List<Throwable> failures() {
        return List.copyOf(failures);
    }

    /** 원 TX 안에서 부르면 그 TX 가 쓰는 MySQL 연결 ID 를 돌려준다. 같은 연결이면 관찰 연결과 ID 가 같다. */
    public static long currentConnectionId(EntityManager entityManager) {
        return ((Number) entityManager.createNativeQuery("SELECT CONNECTION_ID()").getSingleResult()).longValue();
    }

    @Override
    public void close() throws InterruptedException {
        worker.shutdownNow();
        if (!worker.awaitTermination(WAIT.toSeconds(), TimeUnit.SECONDS)) {
            throw new IllegalStateException("관찰 스레드가 끝나지 않았습니다.");
        }
    }
}
