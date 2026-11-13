package com.loopers.coupon.application.port.in;

/**
 * 대기열 (POST·GET /queue, D-4). 업무 상태는 모두 결과의 status로 돌려준다(200). 오류는 404·409·429·503.
 */
public interface CouponQueueUseCase {

    QueueStatusInfo register(Long campaignId, Long userId);

    QueueStatusInfo status(Long campaignId, Long userId);

    enum QueueStatus { WAITING, ADMITTED, ISSUED, SOLD_OUT, NOT_IN_QUEUE }

    /**
     * @param ahead 내 앞 인원 (WAITING일 때만)
     * @param pollAfterMs 다음 조회까지 기다릴 시간 (WAITING일 때만, D-4-5)
     */
    record QueueStatusInfo(QueueStatus status, Long ahead, Long pollAfterMs) {

        public static QueueStatusInfo of(QueueStatus status) {
            return new QueueStatusInfo(status, null, null);
        }
    }
}
