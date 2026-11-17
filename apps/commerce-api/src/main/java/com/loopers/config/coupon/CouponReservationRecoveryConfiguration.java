package com.loopers.config.coupon;

import com.loopers.application.coupon.CouponAdmissionService;
import com.loopers.application.coupon.CouponSelectionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.util.List;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "commerce.coupon.admission.enabled", havingValue = "true")
@EnableScheduling
public class CouponReservationRecoveryConfiguration {
    private static final Logger log = LoggerFactory.getLogger(CouponReservationRecoveryConfiguration.class);
    private final List<Long> campaigns;
    private final CouponAdmissionService admission;
    private final CouponSelectionStore selections;

    public CouponReservationRecoveryConfiguration(@Value("${commerce.coupon.admission.campaign-ids}") List<Long> campaigns,
            CouponAdmissionService admission, CouponSelectionStore selections) {
        if (campaigns.isEmpty() || campaigns.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new IllegalArgumentException("Configure positive coupon campaign IDs before enabling admission");
        }
        this.campaigns = campaigns.stream().distinct().toList();
        this.admission = admission;
        this.selections = selections;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void openConfiguredCampaigns() {
        for (long campaignId : campaigns) {
            try {
                admission.open(campaignId);
            } catch (RuntimeException failure) {
                log.error("Coupon campaign {} remains closed after reconciliation failed", campaignId, failure);
            }
        }
    }

    @Bean
    ThreadPoolTaskScheduler couponReservationScheduler() {
        var scheduler = new ThreadPoolTaskScheduler();
        scheduler.setThreadNamePrefix("coupon-reservation-");
        scheduler.setAwaitTerminationSeconds(10);
        return scheduler;
    }

    @Scheduled(fixedDelayString = "${commerce.coupon.admission.recovery-delay:5s}", scheduler = "couponReservationScheduler")
    public void recoverSelections() {
        for (long campaignId : campaigns) {
            if (selections.isOpen(campaignId)) {
                try {
                    admission.resumePending(campaignId);
                } catch (RuntimeException failure) {
                    log.warn("Coupon reservation recovery paused for this pass, campaign={}", campaignId, failure);
                }
            }
        }
    }
}
