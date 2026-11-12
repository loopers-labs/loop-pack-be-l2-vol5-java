package com.loopers.coupon.application.port.in;

public interface CouponIssueRelayUseCase {

    /**
     * 한 회차: 차례가 된 당첨 기록을 묶어 보내고 broker 응답대로 기록한다.
     */
    RelayResult relayOnce();

    /**
     * @param stoppedAt 이번 회차에서 처음 PUBLISHED가 되지 못한 행 id (없으면 null)
     */
    record RelayResult(int selected, int published, Long stoppedAt) {

        public boolean stopped() {
            return stoppedAt != null;
        }
    }
}
