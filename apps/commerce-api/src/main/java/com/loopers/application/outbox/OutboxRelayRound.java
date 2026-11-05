package com.loopers.application.outbox;

/**
 * relay 한 회차의 결과(docs/week7/decisions.md D-15). 회차 끝의 구조화 로그와 같은 숫자다.
 *
 * @param skipped 앞 회차가 아직 돌고 있어 이번 회차를 건너뛰었다(D-4)
 * @param due     보낼 차례가 되어 고른 행 수(최대 batch-size)
 * @param picked  그중 조건부 UPDATE로 실제로 집은 행 수(INV-704)
 * @param sent    broker가 받았다고 응답한 수
 * @param failed  보내기가 실패한 수(이번 회차에 BLOCKED로 뺀 수 포함)
 * @param blocked 이번 회차에 BLOCKED로 뺀 수(D-3)
 */
public record OutboxRelayRound(boolean skipped, int due, int picked, int sent, int failed, int blocked) {

    static OutboxRelayRound skippedRound() {
        return new OutboxRelayRound(true, 0, 0, 0, 0, 0);
    }
}
