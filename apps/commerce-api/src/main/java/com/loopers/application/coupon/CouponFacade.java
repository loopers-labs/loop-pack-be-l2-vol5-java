package com.loopers.application.coupon;

import com.loopers.config.CouponQueueProperties;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssuanceModel;
import com.loopers.domain.coupon.CouponIssuanceRepository;
import com.loopers.domain.coupon.CouponQueuePort;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.CannotCreateTransactionException;

@Slf4j
@RequiredArgsConstructor
@Service
public class CouponFacade {

    private final CouponCampaignRepository campaignRepository;
    private final CouponIssuanceRepository issuanceRepository;
    private final CouponQueuePort queuePort;
    private final CouponAdmissionGate admissionGate;
    private final CouponIssuanceService issuanceService;
    private final CouponQueueProperties properties;

    public CouponInfo.CampaignDetail createCampaign(
        String name, int totalQuota,
        java.time.ZonedDateTime startAt, java.time.ZonedDateTime endAt
    ) {
        CouponCampaignModel campaign = campaignRepository.save(
            new CouponCampaignModel(name, totalQuota, startAt, endAt)
        );
        return CouponInfo.CampaignDetail.from(campaign);
    }

    public CouponInfo.QueueEntry enqueue(long campaignId, long userId) {
        requireGateOpen(campaignId);
        CouponQueuePort.EnqueueResult result;
        try {
            result = queuePort.enqueue(campaignId, userId, properties.maxQueueSize());
        } catch (Exception e) {
            admissionGate.forceClose(campaignId);
            log.warn("Redis 장애로 대기열 진입 실패, 게이트 닫힘: campaignId={}", campaignId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "대기열 서비스에 일시 오류가 발생했습니다.");
        }
        return switch (result) {
            case ENQUEUED -> {
                int position = getPositionAfterEnqueue(campaignId, userId);
                yield CouponInfo.QueueEntry.of(campaignId, userId, position);
            }
            case ALREADY_QUEUED -> CouponInfo.QueueEntry.of(campaignId, userId, 0);
            case QUEUE_FULL -> throw new CoreException(ErrorType.TOO_MANY_REQUESTS, "대기열이 가득 찼습니다.");
        };
    }

    public CouponInfo.QueueStatus getQueueStatus(long campaignId, long userId) {
        CouponQueuePort.QueueStatusResult result;
        try {
            result = queuePort.status(campaignId, userId, properties.admitTtlSeconds());
        } catch (Exception e) {
            admissionGate.forceClose(campaignId);
            log.warn("Redis 장애로 상태 조회 실패, 게이트 닫힘: campaignId={}", campaignId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "대기열 서비스에 일시 오류가 발생했습니다.");
        }
        return CouponInfo.QueueStatus.from(result);
    }

    public CouponInfo.IssuedCoupon issueCoupon(long campaignId, long userId) {
        requireGateOpen(campaignId);

        CouponQueuePort.StartResult startResult;
        try {
            startResult = queuePort.start(campaignId, userId, properties.admitTtlSeconds());
        } catch (Exception e) {
            admissionGate.forceClose(campaignId);
            log.warn("Redis 장애로 발급 시작 실패, 게이트 닫힘: campaignId={}", campaignId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "대기열 서비스에 일시 오류가 발생했습니다.");
        }

        switch (startResult) {
            case NOT_ADMITTED -> throw new CoreException(ErrorType.BAD_REQUEST, "대기열에 입장 허가를 받지 못했습니다.");
            case EXPIRED -> throw new CoreException(ErrorType.BAD_REQUEST, "입장 허가 시간이 만료되었습니다. 다시 대기열에 진입해 주세요.");
            default -> { }
        }

        try {
            CouponIssuanceModel issuance = issueWithRetry(campaignId, userId);
            return CouponInfo.IssuedCoupon.from(issuance);
        } finally {
            releaseWithRetry(campaignId, userId);
        }
    }

    public CouponInfo.AdmissionStatus openGate(long campaignId) {
        campaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "캠페인을 찾을 수 없습니다."));
        admissionGate.open(campaignId);
        return new CouponInfo.AdmissionStatus(campaignId, true);
    }

    public CouponInfo.AdmissionStatus closeGate(long campaignId) {
        admissionGate.close(campaignId);
        return new CouponInfo.AdmissionStatus(campaignId, false);
    }

    public CouponInfo.AdmissionStatus getAdmissionStatus(long campaignId) {
        return new CouponInfo.AdmissionStatus(campaignId, admissionGate.isOpen(campaignId));
    }

    private void requireGateOpen(long campaignId) {
        if (!admissionGate.isOpen(campaignId)) {
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "현재 쿠폰 발급이 중단되어 있습니다.");
        }
    }

    private int getPositionAfterEnqueue(long campaignId, long userId) {
        try {
            CouponQueuePort.QueueStatusResult s = queuePort.status(campaignId, userId, properties.admitTtlSeconds());
            return s.position();
        } catch (Exception e) {
            return 0;
        }
    }

    private CouponIssuanceModel issueWithRetry(long campaignId, long userId) {
        int maxRetries = 2;
        long baseDelayMs = 50;
        Exception lastEx = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return issuanceService.issue(campaignId, userId);
            } catch (DataIntegrityViolationException e) {
                return issuanceRepository.findByCampaignIdAndUserId(campaignId, userId)
                    .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "발급 조회 실패"));
            } catch (CoreException e) {
                throw e;
            } catch (TransientDataAccessException | CannotCreateTransactionException e) {
                lastEx = e;
                if (attempt < maxRetries) {
                    long jitter = (long) (Math.random() * baseDelayMs);
                    long delay = baseDelayMs * (1L << attempt) + jitter;
                    try {
                        Thread.sleep(delay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    log.warn("쿠폰 발급 재시도 {}/{}: campaignId={}", attempt + 1, maxRetries, campaignId);
                }
            }
        }
        log.error("쿠폰 발급 재시도 소진: campaignId={}", campaignId, lastEx);
        throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "쿠폰 발급 중 일시 오류가 발생했습니다.");
    }

    private void releaseWithRetry(long campaignId, long userId) {
        int maxAttempts = 3;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            try {
                queuePort.release(campaignId, userId);
                return;
            } catch (Exception e) {
                if (attempt == maxAttempts - 1) {
                    log.warn("release 재시도 소진: campaignId={}, userId={}", campaignId, userId, e);
                }
            }
        }
    }
}
