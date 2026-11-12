package com.loopers.application.coupon;

import com.loopers.config.CouponQueueProperties;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponQueuePort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@RequiredArgsConstructor
@Component
public class CouponPromoteScheduler {

    private final CouponQueuePort queuePort;
    private final CouponAdmissionGate admissionGate;
    private final CouponQueueProperties properties;

    private final AtomicBoolean running = new AtomicBoolean(false);

    @Scheduled(fixedDelayString = "${coupon.queue.promote-delay-ms:200}")
    public void promote() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Set<Long> openCampaigns = admissionGate.getAllOpenCampaignIds();
            for (long campaignId : openCampaigns) {
                try {
                    int promoted = queuePort.promote(
                        campaignId,
                        properties.capacity(),
                        properties.heartbeatTimeoutSeconds(),
                        properties.admitTtlSeconds()
                    );
                    if (promoted > 0) {
                        log.debug("대기열 승격: campaignId={}, promoted={}", campaignId, promoted);
                    }
                } catch (Exception e) {
                    log.warn("승격 중 Redis 오류: campaignId={}", campaignId, e);
                    admissionGate.forceClose(campaignId);
                }
            }
        } finally {
            running.set(false);
        }
    }
}
