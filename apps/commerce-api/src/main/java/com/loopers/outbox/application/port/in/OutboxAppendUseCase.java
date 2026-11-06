package com.loopers.outbox.application.port.in;

/**
 * 업무 트랜잭션 안에서 주문 Outbox 행을 남긴다 (docs/week7/plan.md "이미 정해진 전제"). 트랜잭션 밖에서 부르면 예외다 — 확정과 함께 commit돼야 의미가 있다.
 * Kafka로 보내지 않는다. 보내는 것은 relay 한 곳이다 (commit 뒤, TX 밖).
 */
public interface OutboxAppendUseCase {

    void append(OrderOutboxEntry entry);

    /**
     * @param eventId       이벤트 신원 — 최초 확정 TX에서 한 번 만든 값. 재발행해도 같다 (ADR-W6-01)
     * @param orderId       확정된 주문 ID. partition key가 된다
     * @param eventType     이벤트 종류 (예: OrderConfirmed)
     * @param schemaVersion payload 스키마 버전
     * @param payload       직렬화할 본문. 행을 남길 때 한 번만 JSON으로 바꾸고, 이후 바꾸지 않는다
     */
    record OrderOutboxEntry(String eventId, Long orderId, String eventType, int schemaVersion, Object payload) {}
}
