package com.loopers.domain.order;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/** 주문 확정 Outbox 저장·조회 포트(설계 W7.2-5). JPA·Pageable·EntityManager 를 노출하지 않는다. */
public interface OrderConfirmedOutboxRepository {

    /**
     * 호출자의 현재 TX 에 insert 하고 flush 한다. TX 가 없으면 거절하며 단독 commit·upsert 는 하지 않는다.
     * 같은 eventId 의 행이 있으면 DB 유일 제약 오류가 전파된다. flush 성공은 commit 성공이 아니다.
     * 반환값은 영속성 컨텍스트가 관리하는 행이며 이후 같은 TX 에서는 반환값을 사용한다.
     */
    OrderConfirmedOutboxModel insertAndFlush(OrderConfirmedOutboxModel outbox);

    /** 반환한 Entity 는 호출 TX 안에서만 사용한다. */
    Optional<OrderConfirmedOutboxModel> findByEventId(String eventId);

    /**
     * {@code status=PENDING AND next_attempt_at <= now} 행의 ID 를 next_attempt_at 오름차순, 같은 시각이면 ID 오름차순으로
     * 최대 limit 개 반환한다. limit 은 양수여야 한다. 이 순서는 commit 순서나 업무 이벤트의 발행 순서 보장이 아니다.
     */
    List<Long> findPendingDueIds(ZonedDateTime now, int limit);
}
