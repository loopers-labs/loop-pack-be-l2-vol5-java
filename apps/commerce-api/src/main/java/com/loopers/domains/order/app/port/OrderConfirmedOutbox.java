package com.loopers.domains.order.app.port;

import java.time.Instant;
import java.util.List;

public interface OrderConfirmedOutbox {
    // 주문을 확정한 트랜잭션 안에서 부른다. 실패하면 예외로 올려 확정까지 함께 rollback되게 한다.
    void append(OrderConfirmedPayload payload);

    // status = PENDING이고 next_attempt_at이 지난 행을 next_attempt_at, id 순으로 limit개까지 고른다.
    List<OutboxMessage> findDue(Instant now, int limit);

    // 상태 기록은 모두 PENDING인 행에만 적용하고 영향 행 수를 돌려준다. PUBLISHED·BLOCKED는 되돌리지 않는다(D115).
    int markPublished(long id, Instant publishedAt);

    int markBrokerFailure(long id, Instant nextAttemptAt, String error);

    int markRowFailure(long id, Instant nextAttemptAt, String error, boolean blocked);

    // 사람이 원인을 고친 뒤 BLOCKED 행 하나를 다시 보낼 차례로 돌린다. BLOCKED인 행에만 적용하고 영향 행 수를 돌려준다.
    int resumeBlocked(String eventId, Instant now);

    // 같은 eventId의 행이 있는지, 있다면 저장된 payload가 이 payload를 직렬화한 값과 같은지 본다. 복구 도구가 쓴다.
    StoredComparison compare(OrderConfirmedPayload payload);

    enum StoredComparison { ABSENT, SAME, DIFFERENT }
}
