package com.loopers.application.shopping.service;

import com.loopers.application.shopping.event.ProductLikeChangedEvent;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
// 커밋된 좋아요 변경을 상품별 증감분으로 메모리에 누적하는 버퍼
public class LikeCountDeltaBuffer {
    private final ConcurrentHashMap<Long, Long> deltas = new ConcurrentHashMap<>();

    // 트랜잭션이 커밋된 뒤에만 증감분 누적
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void on(ProductLikeChangedEvent event) {
        deltas.merge(event.productId(), event.delta(), Long::sum);
    }

    // 누적분을 상품별로 원자적으로 꺼내고 비운다. 합이 0인 상품은 제외
    public Map<Long, Long> drain() {
        Map<Long, Long> drained = new HashMap<>();
        for (Long productId : deltas.keySet()) {
            Long delta = deltas.remove(productId);
            if (delta != null && delta != 0L) {
                drained.put(productId, delta);
            }
        }
        return drained;
    }

    // 반영에 실패한 증감분을 되돌려 새 증감분과 합산
    public void restore(Map<Long, Long> restored) {
        restored.forEach((productId, delta) -> deltas.merge(productId, delta, Long::sum));
    }
}
