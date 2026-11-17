package com.loopers.interfaces.scheduler;

import com.loopers.application.coupon.CouponIssuanceWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.List;

public class CouponIssuanceSchedule {
    private static final Logger log = LoggerFactory.getLogger(CouponIssuanceSchedule.class);
    private final CouponIssuanceWorker worker;
    private final List<Long> campaigns;

    public CouponIssuanceSchedule(CouponIssuanceWorker worker, List<Long> campaigns) {
        this.worker = worker;
        this.campaigns = List.copyOf(campaigns);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void open() {
        try {
            worker.open();
        } catch (RuntimeException failure) {
            log.error("Coupon issuance remains closed after startup reconciliation failed", failure);
        }
    }

    @Scheduled(fixedDelayString = "${commerce.coupon.worker.poll-delay:1s}", scheduler = "couponIssuanceScheduler")
    public void poll() {
        worker.poll(campaigns);
    }
}
