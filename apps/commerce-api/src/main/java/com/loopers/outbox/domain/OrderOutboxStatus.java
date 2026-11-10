package com.loopers.outbox.domain;

/**
 * 주문 Outbox 행의 상태 (docs/week7/plan.md D-0).
 * <ul>
 *   <li>PENDING — 보낼 차례를 기다린다. {@code next_attempt_at}이 지나면 relay가 고른다</li>
 *   <li>SENDING — relay가 맡아서 보내는 중이다. {@code next_attempt_at}은 맡은 기한(lease)이고, 지나면 다시 고른다</li>
 *   <li>PUBLISHED — broker가 저장을 확인했다(ack). 더 바뀌지 않는다</li>
 *   <li>BLOCKED — 3번 실패해 자동 재시도를 멈췄다. 사람이 재개하면 PENDING으로 돌아간다 (보내는 쪽의 DLT, D-3)</li>
 * </ul>
 */
public enum OrderOutboxStatus {
    PENDING,
    SENDING,
    PUBLISHED,
    BLOCKED
}
