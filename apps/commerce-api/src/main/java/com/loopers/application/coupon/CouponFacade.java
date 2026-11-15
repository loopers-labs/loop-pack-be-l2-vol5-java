package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueCancellationRepository;
import com.loopers.domain.coupon.CouponIssueGate;
import com.loopers.domain.coupon.CouponIssueJobs;
import com.loopers.domain.coupon.CouponIssueLimiter;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 발급 경로 세 가지를 제공한다. DB_ONLY·REDIS_SYNC는 DB commit 뒤 실제 쿠폰으로 답한다.
 * QUEUE는 Redis 판단 + 작업 큐 등록 뒤 접수(PENDING)로 답하고, DB 저장은 {@link CouponIssueWorker}가 한다.
 */
@Component
@Slf4j
public class CouponFacade {
    private final UserService users;
    private final CouponRepository coupons;
    private final CouponCampaignRepository campaigns;
    private final CouponIssueTransaction transaction;
    private final CouponIssueGate gate;
    private final CouponIssueLimiter limiter;
    private final CouponIssueJobs jobs;
    private final CouponIssueCancellationRepository cancellations;
    private final CouponClosedCampaigns closed;
    private final Clock clock;
    private final CouponIssueMode mode;
    private final Duration retention;
    private final Duration queueRetention;

    public CouponFacade(UserService users, CouponRepository coupons, CouponCampaignRepository campaigns,
        CouponIssueTransaction transaction, CouponIssueGate gate, CouponIssueLimiter limiter, CouponIssueJobs jobs,
        CouponIssueCancellationRepository cancellations, CouponClosedCampaigns closed, Clock clock,
        @Value("${commerce.coupon.issue-mode:REDIS_SYNC}") CouponIssueMode mode,
        @Value("${commerce.coupon.issue-gate.retention-after-end:1h}") Duration retention,
        @Value("${commerce.coupon.issue-queue.retention-after-end:7d}") Duration queueRetention) {
        if (retention.isNegative() || retention.isZero()) throw new IllegalArgumentException("Coupon gate retention must be positive");
        this.users = users;
        this.coupons = coupons;
        this.campaigns = campaigns;
        this.transaction = transaction;
        this.gate = gate;
        this.limiter = limiter;
        this.jobs = jobs;
        this.cancellations = cancellations;
        this.closed = closed;
        this.clock = clock;
        this.mode = mode;
        this.retention = retention;
        this.queueRetention = queueRetention;
    }

    public CouponIssueResult issue(Long userId, Long campaignId) {
        if (userId == null || userId <= 0) throw new CoreException(ErrorType.BAD_REQUEST, "X-USER-ID는 양수여야 합니다.");
        if (campaignId == null || campaignId <= 0) throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 ID는 양수여야 합니다.");
        if (mode == CouponIssueMode.DB_ONLY) {
            users.get(userId);
            var existing = coupons.findByCampaignIdAndUserId(campaignId, userId);
            if (existing.isPresent()) return CouponIssueResult.existing(CouponInfo.from(existing.get()));
            return issueAfterReservation(userId, campaignId, null);
        }
        // 이 서버가 기억한 마감(최종 매진·종료)이면 Redis에 가지 않는다. 받은 사람인지 확인하지 않으므로 쿠폰함 안내를 붙인 409다.
        closed.rejection(campaignId).ifPresent(rejection -> {
            throw new CoreException(ErrorType.CONFLICT, rejection);
        });
        if (mode == CouponIssueMode.QUEUE) return accept(userId, campaignId);
        // REDIS_SYNC: Redis가 먼저 판단한다. 매진·종료는 DB에 가지 않는다. 이미 통과한 사용자는 실제 쿠폰만 DB에서 읽는다.
        CouponIssueGate.Entry entry;
        try {
            entry = gate.enter(campaignId, userId, clock.instant());
        } catch (RuntimeException failure) {
            // 판단할 수 없으면 새 발급은 하지 않는다. 이미 받은 쿠폰만 DB에서 찾아 돌려준다.
            log.warn("Coupon Redis judgment unavailable, campaignId={}", campaignId, failure);
            return coupons.findByCampaignIdAndUserId(campaignId, userId)
                .map(coupon -> CouponIssueResult.existing(CouponInfo.from(coupon)))
                .orElseThrow(() -> new CoreException(ErrorType.SERVICE_UNAVAILABLE, CouponRejections.GATE_UNAVAILABLE));
        }
        switch (entry.decision()) {
            case PASSED -> { }
            // 이미 통과한 사용자다. 앞 요청이 commit됐으면 실제 쿠폰을, 아직이면 처리 중을 답한다.
            case ALREADY_ACCEPTED, ALREADY_RESERVED -> {
                return alreadyPassed(userId, campaignId);
            }
            case CANCELLED -> throw new CoreException(ErrorType.CONFLICT, CouponRejections.CANCELLED);
            case SOLD_OUT -> throw new CoreException(ErrorType.CONFLICT, CouponRejections.SOLD_OUT);
            case SOLD_OUT_FINAL -> throw closedNow(campaignId, CouponRejections.SOLD_OUT, CouponRejections.CLOSED_SOLD_OUT);
            case ENDED -> throw closedNow(campaignId, CouponRejections.ENDED, CouponRejections.CLOSED_ENDED);
            case NOT_PREPARED -> throw notPrepared(campaignId);
        }
        String reservation = entry.reservation();
        // 사용자 확인은 Redis를 통과한 요청만 한다. 없는 사용자면 가져간 자리를 돌려준다.
        try {
            users.get(userId);
        } catch (RuntimeException failure) {
            cancelQuietly(campaignId, userId, reservation);
            throw failure;
        }
        return issueAfterReservation(userId, campaignId, reservation);
    }

    /**
     * 작업 큐 모드의 접수. Redis Lua 한 번으로 판단과 작업 등록을 하고 바로 답한다. DB에는 가지 않는다.
     * 사용자 확인과 DB 발급은 Worker가 한다. 이미 통과한 사용자는 저장이 끝났으면 쿠폰을, 아니면 접수 상태를 돌려준다.
     */
    private CouponIssueResult accept(Long userId, Long campaignId) {
        CouponIssueGate.Entry entry;
        try {
            entry = gate.enqueue(campaignId, userId, clock.instant());
        } catch (RuntimeException failure) {
            log.warn("Coupon Redis judgment unavailable, campaignId={}", campaignId, failure);
            return coupons.findByCampaignIdAndUserId(campaignId, userId)
                .map(coupon -> CouponIssueResult.existing(CouponInfo.from(coupon)))
                .orElseThrow(() -> new CoreException(ErrorType.SERVICE_UNAVAILABLE, CouponRejections.GATE_UNAVAILABLE));
        }
        return switch (entry.decision()) {
            case PASSED -> CouponIssueResult.pending(campaignId);
            case ALREADY_ACCEPTED, ALREADY_RESERVED -> coupons.findByCampaignIdAndUserId(campaignId, userId)
                .map(coupon -> CouponIssueResult.existing(CouponInfo.from(coupon)))
                .orElseGet(() -> CouponIssueResult.pending(campaignId));
            // 확정 취소된 사용자는 같은 캠페인에 다시 받지 않는다(정책. 재신청을 허용하려면 접수마다 결과를 구분해야 한다).
            case CANCELLED -> throw new CoreException(ErrorType.CONFLICT, CouponRejections.CANCELLED);
            case SOLD_OUT -> throw new CoreException(ErrorType.CONFLICT, CouponRejections.SOLD_OUT);
            case SOLD_OUT_FINAL -> throw closedNow(campaignId, CouponRejections.SOLD_OUT, CouponRejections.CLOSED_SOLD_OUT);
            case ENDED -> throw closedNow(campaignId, CouponRejections.ENDED, CouponRejections.CLOSED_ENDED);
            case NOT_PREPARED -> throw notPrepared(campaignId);
        };
    }




    /** 쿠폰이 저장됐는지, 처리 중인지, 취소됐는지 알려 준다. 사용자 확인 없이 기록만 본다. */
    @Transactional(readOnly = true)
    public CouponIssueStatus getIssueStatus(Long userId, Long campaignId) {
        if (userId == null || userId <= 0) throw new CoreException(ErrorType.BAD_REQUEST, "X-USER-ID는 양수여야 합니다.");
        if (campaignId == null || campaignId <= 0) throw new CoreException(ErrorType.BAD_REQUEST, "캠페인 ID는 양수여야 합니다.");
        var coupon = coupons.findByCampaignIdAndUserId(campaignId, userId);
        if (coupon.isPresent()) return CouponIssueStatus.issued(CouponInfo.from(coupon.get()));
        var cancellation = cancellations.find(campaignId, userId);
        if (cancellation.isPresent()) return CouponIssueStatus.cancelled(campaignId, cancellation.get().getReason());
        CouponIssueGate.UserState state;
        try {
            state = gate.state(campaignId, userId);
        } catch (RuntimeException failure) {
            log.warn("Coupon Redis judgment unavailable, campaignId={}", campaignId, failure);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, CouponRejections.GATE_UNAVAILABLE);
        }
        return state == CouponIssueGate.UserState.NONE ? CouponIssueStatus.none(campaignId) : CouponIssueStatus.pending(campaignId);
    }

    /** Redis가 이미 통과시킨 사용자. 실제 쿠폰이 있으면 돌려주고, 아직 commit 전이면 처리 중이다. */
    private CouponIssueResult alreadyPassed(Long userId, Long campaignId) {
        return coupons.findByCampaignIdAndUserId(campaignId, userId)
            .map(coupon -> CouponIssueResult.existing(CouponInfo.from(coupon)))
            .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, CouponRejections.IN_PROGRESS));
    }

    /** 다시 풀리지 않는 마감을 이 서버에 기억한다. 이번 요청에는 Redis가 판단한 그대로 답한다. */
    private CoreException closedNow(Long campaignId, String rejection, String remembered) {
        closed.remember(campaignId, remembered);
        return new CoreException(ErrorType.CONFLICT, rejection);
    }

    private CoreException notPrepared(Long campaignId) {
        if (campaigns.find(campaignId).isEmpty()) return new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다.");
        return new CoreException(ErrorType.SERVICE_UNAVAILABLE, CouponRejections.NOT_PREPARED);
    }

    /** reservation이 null이면 DB_ONLY다. 실패가 확정됐거나 TX에 들어가지 못했을 때만 Redis 예약을 돌린다. */
    private CouponIssueResult issueAfterReservation(Long userId, Long campaignId, String reservation) {
        CouponIssueExecution execution = new CouponIssueExecution();
        try {
            CouponIssueResult result = issueWithinLimit(userId, campaignId, execution);
            if (reservation != null) confirmQuietly(campaignId, userId, reservation, result.created());
            return result;
        } catch (RuntimeException failure) {
            // rollback/시작 전 실패만 보상한다. 결과 불명은 R을 유지하고 DB 확인 후 수동으로 재준비한다.
            if (reservation != null && (execution.isCompleted() || !execution.isStarted())) {
                cancelQuietly(campaignId, userId, reservation);
            }
            throw failure;
        }
    }

    /**
     * DB 발급 TX 앞에서 자리(분산락·세마포어)를 기다린다. 기본 설정은 기다리지 않는다.
     * 자리를 못 얻으면(바로 거절이든 기다리다 시간 초과든) 429, 자리 확인이 안 되면 503이다.
     * 둘 다 TX에 들어가지 않은 실패라 호출자가 Redis 예약을 돌린다.
     */
    private CouponIssueResult issueWithinLimit(Long userId, Long campaignId, CouponIssueExecution execution) {
        boolean acquired;
        try {
            acquired = limiter.acquire(campaignId);
        } catch (RuntimeException failure) {
            log.warn("Coupon issue limit unavailable, campaignId={}", campaignId, failure);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, CouponRejections.LIMIT_UNAVAILABLE);
        }
        // 자리가 꽉 찬 것은 의도한 거절이라 429다. 자리 확인 자체가 안 되는 장애(위)는 503으로 구분한다.
        if (!acquired) throw new CoreException(ErrorType.TOO_MANY_REQUESTS, CouponRejections.LIMIT_NO_SEAT);
        try {
            return issueInDb(userId, campaignId, execution);
        } finally {
            releaseQuietly(campaignId);
        }
    }

    // 반환 실패로 DB 결과를 뒤집지 않는다. 자리는 락 만료(watchdog 중단) 전까지 남을 수 있다.
    private void releaseQuietly(Long campaignId) {
        try {
            limiter.release(campaignId);
        } catch (RuntimeException failure) {
            log.error("Coupon issue limit release failed, campaignId={}", campaignId, failure);
        }
    }

    private CouponIssueResult issueInDb(Long userId, Long campaignId, CouponIssueExecution execution) {
        try {
            return transaction.issue(userId, campaignId, execution);
        } catch (RuntimeException failure) {
            if (execution.isStarted() && !execution.isCompleted()) {
                throw new CoreException(ErrorType.SERVICE_UNAVAILABLE, "쿠폰 발급 결과를 확인하지 못했습니다. 쿠폰함에서 확인해 주세요.");
            }
            boolean rejected = failure instanceof CoreException core && core.getErrorType() == ErrorType.CONFLICT;
            if (execution.isCompleted() && (rejected || isCouponDuplicate(failure))) {
                // UNIQUE 전에 마지막 한도의 UPDATE 0건이 될 수도 있다. rollback 뒤 새 조회로 확인한다.
                var committed = coupons.findByCampaignIdAndUserId(campaignId, userId);
                if (committed.isPresent()) return CouponIssueResult.existing(CouponInfo.from(committed.get()));
            }
            throw failure;
        }
    }

    private void confirmQuietly(Long campaignId, Long userId, String reservation, boolean created) {
        try {
            if (!gate.confirm(campaignId, userId, reservation, created)) {
                log.error("Coupon reservation changed after DB commit, campaignId={}, userId={}", campaignId, userId);
            }
        } catch (RuntimeException failure) {
            // DB 결과를 Redis 오류로 뒤집지 않는다. 실제 쿠폰은 보존되고 재요청은 DB 기존 결과를 읽는다.
            log.error("Coupon Redis confirmation failed after commit, campaignId={}, userId={}", campaignId, userId, failure);
        }
    }

    private void cancelQuietly(Long campaignId, Long userId, String reservation) {
        try {
            gate.cancel(campaignId, userId, reservation);
        } catch (RuntimeException failure) {
            log.error("Coupon Redis compensation failed; reprepare after DB reconciliation, campaignId={}, userId={}", campaignId, userId, failure);
        }
    }

    /** 호출자가 유입 중단·모든 실행/DB 종료를 확인한 뒤 DB 기준으로 재준비한다. 이 전제는 API가 자동 강제하지 않는다. */

    @Transactional(readOnly = true)
    public CouponIssueGatePreparation prepareIssueGate(Long campaignId) {
        var campaign = campaigns.find(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "쿠폰 캠페인을 찾을 수 없습니다."));
        if (!campaign.isActive()) throw new CoreException(ErrorType.CONFLICT, CouponRejections.INACTIVE);
        if (!campaign.getEndAt().isAfter(clock.instant())) throw new CoreException(ErrorType.CONFLICT, CouponRejections.ENDED);
        var issuedUsers = coupons.findUserIdsByCampaignId(campaignId);
        int remaining = Math.max(0, campaign.getTotalQuota() - campaign.getIssuedCount());
        // 작업 큐는 종료 뒤에도 남은 작업을 처리해야 해, 판단 정보를 더 오래 둔다. 다 처리되면 Worker가 먼저 지운다.
        var expireAt = campaign.getEndAt().plus(mode == CouponIssueMode.QUEUE ? queueRetention : retention);
        gate.prepare(campaignId, remaining, issuedUsers, campaign.getEndAt(), expireAt);
        closed.forget(campaignId);
        // 작업 큐도 비우고 소비자 그룹을 새로 만든다. 같은 전제(발급 요청·처리 중 작업 없음)에서만 부른다.
        if (mode == CouponIssueMode.QUEUE) jobs.reset(campaignId);
        return new CouponIssueGatePreparation(campaignId, remaining, issuedUsers.size(), campaign.getEndAt(), expireAt);
    }

    @Transactional(readOnly = true)
    public List<CouponInfo> getMyCoupons(Long userId) {
        requireUser(userId);
        return coupons.findAllByUserId(userId).stream().map(CouponInfo::from).toList();
    }

    private void requireUser(Long userId) {
        if (userId == null || userId <= 0) throw new CoreException(ErrorType.BAD_REQUEST, "X-USER-ID는 양수여야 합니다.");
        users.get(userId);
    }

    private static boolean isCouponDuplicate(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause.getMessage() != null && cause.getMessage().contains("uk_coupon_campaign_user")) return true;
        }
        return false;
    }
}
