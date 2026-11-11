package com.loopers.application.order;

/**
 * relay 한 회차의 결과 (7주차 설계 D22·D31). 테스트 단언과 회차 요약 로그가 같은 값을 쓴다.
 *
 * @param reason           회차가 끝난 이유
 * @param picked           이번 회차에 집은 PENDING 행 수
 * @param published        broker 확인 뒤 PUBLISHED 로 바꾼 수
 * @param retried          일시 실패·미확인으로 재시도를 예약한 수
 * @param blocked          영구 오류로 BLOCKED 로 바꾼 수
 * @param skippedStale     집었지만 시도 직전에 상태가 이미 바뀌어 보내지 않은 수. 단일 relay 에서는 0 이어야 하며 0 이 아니면 이상 신호다
 * @param deferred         broker 불가로 회차를 일찍 끝내 다음 회차로 미룬 수 (정상 동작)
 * @param remainingPending 회차 끝 시점의 PENDING 행 수 (관찰용). ALREADY_RUNNING 이면 집지도 세지도 않았다는 뜻으로 -1
 */
public record OutboxRelayResult(
    Reason reason,
    int picked,
    int published,
    int retried,
    int blocked,
    int skippedStale,
    int deferred,
    long remainingPending
) {

    public enum Reason {
        /** 묶음을 끝까지 처리했다. */
        COMPLETED,
        /** broker 가 응답하지 않아(전송 실패·미확인) 그 행만 재시도 예약하고 회차를 일찍 끝냈다 (D24). */
        BROKER_UNAVAILABLE,
        /** 같은 프로세스에서 다른 회차가 실행 중이라 집지 않았다 (D22). */
        ALREADY_RUNNING
    }

    public static OutboxRelayResult alreadyRunning() {
        return new OutboxRelayResult(Reason.ALREADY_RUNNING, 0, 0, 0, 0, 0, 0, -1);
    }
}
