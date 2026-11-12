package com.loopers.domain.coupon;

public interface CouponQueuePort {

    enum EnqueueResult { ENQUEUED, ALREADY_QUEUED, QUEUE_FULL }

    enum StartResult { STARTED, NOT_ADMITTED, EXPIRED }

    enum QueueStatus { QUEUED, ADMITTED, ACTIVE, NONE }

    record QueueStatusResult(QueueStatus status, int position) {
        public static QueueStatusResult of(QueueStatus status) {
            return new QueueStatusResult(status, 0);
        }
        public static QueueStatusResult queued(int position) {
            return new QueueStatusResult(QueueStatus.QUEUED, position);
        }
    }

    EnqueueResult enqueue(long campaignId, long userId, int maxQueueSize);

    int promote(long campaignId, int capacity, long heartbeatTimeoutSeconds, long admitTtlSeconds);

    StartResult start(long campaignId, long userId, long admitTtlSeconds);

    void release(long campaignId, long userId);

    QueueStatusResult status(long campaignId, long userId, long admitTtlSeconds);
}
