package com.loopers.testsupport;

import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderEventPublisher;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 실제 최초 PG 확정을 실행하는 회귀에만 명시적으로 넣는 기록용 발행 포트. producer 를 만들거나 broker 에 쓰지 않고 호출된 사건만 기록하므로
 * broker 저장 근거가 아니다. 관찰 hook 은 publish 안에서 동기로 실행되어, 포트가 불린 그 시점(AFTER_COMMIT)의 상태를 테스트가 확인할 수 있다.
 *
 * <p>각 테스트는 {@code @TestBean(name = "kafkaOrderEventPublisher", methodName = FACTORY, enforceOverride = true)} 로 실제 adapter 를 바꾼다.
 * factory 를 하나로 두면 같은 구성의 테스트끼리 Spring 컨텍스트 캐시를 함께 쓴다.
 */
public class RecordingOrderEventPublisher implements OrderEventPublisher {

    public static final String FACTORY = "com.loopers.testsupport.RecordingOrderEventPublisher#create";

    private static final Duration RESET_WAIT = Duration.ofSeconds(5);
    private static final Consumer<OrderConfirmed> NO_HOOK = event -> { };

    private final List<OrderConfirmed> published = new CopyOnWriteArrayList<>();
    private final AtomicInteger inFlight = new AtomicInteger();
    private volatile Consumer<OrderConfirmed> hook = NO_HOOK;

    static RecordingOrderEventPublisher create() {
        return new RecordingOrderEventPublisher();
    }

    @Override
    public void publish(OrderConfirmed event) {
        inFlight.incrementAndGet();
        try {
            published.add(event);
            hook.accept(event);
        } finally {
            inFlight.decrementAndGet();
        }
    }

    public List<OrderConfirmed> published() {
        return List.copyOf(published);
    }

    /** 포트 호출 시점에 실행할 관찰. 여기서 던진 예외는 listener 가 포트 외곽 오류로 기록한다. */
    public void onPublish(Consumer<OrderConfirmed> hook) {
        this.hook = hook;
    }

    /** 캐시된 컨텍스트를 다음 테스트가 재사용하므로, 진행 중 호출이 끝나기를 기다린 뒤 기록·hook 을 비운다. */
    public void reset() {
        long deadline = System.nanoTime() + RESET_WAIT.toNanos();
        while (inFlight.get() > 0) {
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("진행 중인 주문 발행 포트 호출이 끝나지 않았습니다.");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        hook = NO_HOOK;
        published.clear();
    }
}
