package com.loopers.application.payment;

import jakarta.persistence.EntityManager;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 테스트용 seam 구현. (지점, 작업, 주문) 에 등록한 동작을 그 TX 스레드에서 실행해 장벽·실패 주입을 만든다.
 * commit 직전 동작은 먼저 flush 해 실제 SQL 이 실행된 뒤에 일어나게 한다. 등록이 없으면 운영 구현처럼 아무것도 하지 않는다.
 */
public class ControllableProbe implements PaymentTransactionProbe {

    private final Map<Key, Runnable> actions = new ConcurrentHashMap<>();
    private final EntityManager entityManager;

    public ControllableProbe(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    public void on(Point point, String operation, Long orderId, Runnable action) {
        actions.put(new Key(point, operation, orderId), action);
    }

    public void clear() {
        actions.clear();
    }

    @Override
    public void reach(Point point, String operation, Long orderId) {
        Runnable action = actions.get(new Key(point, operation, orderId));
        if (action == null) {
            return;
        }
        if (point == Point.BEFORE_COMMIT) {
            entityManager.flush();
        }
        action.run();
    }

    private record Key(Point point, String operation, Long orderId) {
    }
}
