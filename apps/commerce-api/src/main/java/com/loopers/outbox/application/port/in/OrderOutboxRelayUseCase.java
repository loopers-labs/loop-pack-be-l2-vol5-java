package com.loopers.outbox.application.port.in;

/**
 * relay 한 회차 (docs/week7/plan.md D-1). scheduler tick 한 번 또는 수동 재개 한 번이 부른다.
 */
public interface OrderOutboxRelayUseCase {

    RelayResult relayOnce();

    /**
     * @param selected  이번 회차가 고른 행 수
     * @param published 그중 PUBLISHED로 기록한 행 수
     * @param stoppedAt 실패해서 회차를 멈춘 행의 eventId. 끝까지 처리했으면 null
     */
    record RelayResult(int selected, int published, String stoppedAt) {

        public boolean stopped() {
            return stoppedAt != null;
        }
    }
}
