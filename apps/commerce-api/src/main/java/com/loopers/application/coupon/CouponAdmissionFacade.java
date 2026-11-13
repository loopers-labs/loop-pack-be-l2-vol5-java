package com.loopers.application.coupon;

import com.loopers.config.coupon.CouponAdmissionProperties;
import com.loopers.config.coupon.CouponAdmissionSchedulingConfig;
import com.loopers.domain.coupon.Coupon;
import com.loopers.domain.coupon.CouponAdmissionGate;
import com.loopers.domain.coupon.CouponAdmissionGate.CloseReason;
import com.loopers.domain.coupon.CouponAdmissionQueue;
import com.loopers.domain.coupon.CouponAdmissionQueue.EnqueueResult;
import com.loopers.domain.coupon.CouponCampaign;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponIssueCircuit;
import com.loopers.domain.coupon.CouponIssueService;
import com.loopers.domain.coupon.CouponIssueService.IssueResult;
import com.loopers.domain.coupon.CouponPermitSemaphore;
import com.loopers.domain.coupon.CouponPermitSemaphore.ClaimOutcome;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ScheduledFuture;
import java.util.function.Supplier;

/**
 * 쿠폰 발급 수용량 통제의 조립 (8주차 설계 §5·§7·§8). 트랜잭션을 갖지 않는다. DB 구간은 CouponIssueService 가 자기 TX 로 처리하고,
 * 이 클래스는 그 앞뒤의 Redis 수용 판단·자리 반납·장애 정책을 맡는다 (OrderFacade.confirm 과 같은 구조).
 *
 * 장애 정책의 분담 (§7-1·§7-4):
 * - Redis 예외·Lua 해석 불가·세대 불일치 → gate 를 이유와 함께 닫고 503. 이후 요청은 gate 가 닫혀 있어 Redis 를 호출하지 않는다
 * - DB 일시 오류 → 재시도 2회(TransientDataAccessException 만). 소진되면 서킷 실패 1건 + 503. gate 는 닫지 않는다
 * - 자리(permits)는 결과와 무관하게 항상 반납(3회 재시도). 승격(admitted)은 업무 결과가 확정된 경우에만 지운다 (D15)
 */
@Slf4j
@Component
public class CouponAdmissionFacade {

    static final int DB_RETRIES = 2;
    static final int RELEASE_RETRIES = 3;

    public enum ApplyOutcome {
        /** 신규 접수 (202). seq 는 접수 번호 */
        ACCEPTED,
        /** 이미 접수됨 (200). 대기 중이면 seq 는 첫 접수 번호, 승격됐으면 0 */
        ALREADY_ACCEPTED,
        /** 이미 발급됨 (200). couponId 반환 */
        ALREADY_ISSUED
    }

    public record ApplyInfo(ApplyOutcome outcome, long seq, Long couponId) { }

    public enum Status {
        WAITING, ADMITTED, ISSUED, SOLD_OUT, NONE
    }

    public record StatusInfo(Status status, Long rank, Long couponId) { }

    /**
     * @param created true 면 이번 호출로 새로 발급됐다 (201). false 면 기존 쿠폰이다 (200)
     */
    public record ClaimInfo(Long couponId, boolean created) { }

    private final UserRepository userRepository;
    private final CouponCampaignRepository couponCampaignRepository;
    private final CouponRepository couponRepository;
    private final CouponIssueService couponIssueService;
    private final CouponAdmissionGate gate;
    private final CouponAdmissionQueue queue;
    private final CouponPermitSemaphore semaphore;
    private final CouponIssueCircuit circuit;
    private final CouponAdmissionProperties properties;
    private final CouponAdmissionMetrics metrics;
    private final TaskScheduler heartbeatScheduler;

    public CouponAdmissionFacade(
        UserRepository userRepository,
        CouponCampaignRepository couponCampaignRepository,
        CouponRepository couponRepository,
        CouponIssueService couponIssueService,
        CouponAdmissionGate gate,
        CouponAdmissionQueue queue,
        CouponPermitSemaphore semaphore,
        CouponIssueCircuit circuit,
        CouponAdmissionProperties properties,
        CouponAdmissionMetrics metrics,
        @Qualifier(CouponAdmissionSchedulingConfig.COUPON_ADMISSION_TASK_SCHEDULER) TaskScheduler heartbeatScheduler
    ) {
        this.userRepository = userRepository;
        this.couponCampaignRepository = couponCampaignRepository;
        this.couponRepository = couponRepository;
        this.couponIssueService = couponIssueService;
        this.gate = gate;
        this.queue = queue;
        this.semaphore = semaphore;
        this.circuit = circuit;
        this.properties = properties;
        this.metrics = metrics;
        this.heartbeatScheduler = heartbeatScheduler;
    }

    /** 접수 (§5-1). 기존 쿠폰 → 캠페인 활성·gate → 접수 Lua. */
    public ApplyInfo apply(Long campaignId, Long userId) {
        requireIdentifiedUser(userId);
        CouponCampaign campaign = requireCampaign(campaignId);
        Optional<Coupon> existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) {
            return new ApplyInfo(ApplyOutcome.ALREADY_ISSUED, 0L, existing.get().getId());
        }
        requireActive(campaign);
        String gen = requireOpenGate(campaignId);

        EnqueueResult result = redis(campaignId, () -> queue.enqueue(campaignId, userId, acceptLimitOf(campaign), gen));
        return switch (result.outcome()) {
            case ACCEPTED -> new ApplyInfo(ApplyOutcome.ACCEPTED, result.seq(), null);
            case ALREADY_ACCEPTED -> new ApplyInfo(ApplyOutcome.ALREADY_ACCEPTED, result.seq(), null);
            case SOLD_OUT -> throw reject("sold_out", ErrorType.CONFLICT, "쿠폰이 모두 소진되었습니다.");
            case STALE_GENERATION -> throw staleGeneration(campaignId);
        };
    }

    /**
     * 상태 조회 (§8-1). 읽기만 한다. ISSUED 는 DB, WAITING·ADMITTED 는 Redis, 그 외는 캠페인 수량으로 판정한다.
     * 읽기는 상태를 바꾸지 않으므로 Redis 예외가 나도 gate 를 닫지 않고 503 만 돌려준다. 장애 감지는 접수·claim·승격 경로가 맡는다 (§7-1).
     */
    public StatusInfo status(Long campaignId, Long userId) {
        requireIdentifiedUser(userId);
        CouponCampaign campaign = requireCampaign(campaignId);
        Optional<Coupon> existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) {
            return new StatusInfo(Status.ISSUED, null, existing.get().getId());
        }
        try {
            Optional<Long> rank = queue.waitingRank(campaignId, userId);
            if (rank.isPresent()) {
                return new StatusInfo(Status.WAITING, rank.get(), null);
            }
            if (queue.isAdmitted(campaignId, userId)) {
                return new StatusInfo(Status.ADMITTED, null, null);
            }
            boolean soldOut = campaign.getIssuedCount() >= campaign.getTotalQuota()
                || queue.acceptedCount(campaignId) >= acceptLimitOf(campaign);
            return new StatusInfo(soldOut ? Status.SOLD_OUT : Status.NONE, null, null);
        } catch (DataAccessException e) {
            log.warn("상태 조회 중 Redis 호출 실패. 읽기 경로라 gate 는 닫지 않는다. campaignId={}", campaignId, e);
            throw new CoreException(ErrorType.SERVICE_UNAVAILABLE);
        }
    }

    /** 발급 요청 (§5-3). 서킷 허가 → 세마포어 → DB(재시도) → finally 자리 반납·조건부 승격 제거. */
    public ClaimInfo claim(Long campaignId, Long userId) {
        requireIdentifiedUser(userId);
        CouponCampaign campaign = requireCampaign(campaignId);
        Optional<Coupon> existing = couponRepository.findByCampaignIdAndUserId(campaignId, userId);
        if (existing.isPresent()) {
            return new ClaimInfo(existing.get().getId(), false);
        }
        requireActive(campaign);
        String gen = requireOpenGate(campaignId);

        if (!circuit.tryAcquire()) {
            throw reject("db_unavailable", ErrorType.SERVICE_UNAVAILABLE, "쿠폰 발급 저장소를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        }
        String token = UUID.randomUUID().toString();
        ClaimOutcome outcome;
        try {
            outcome = redis(campaignId, () -> semaphore.tryAcquire(campaignId, userId, token, gen));
        } catch (RuntimeException e) {
            circuit.release();
            throw e;
        }
        switch (outcome) {
            case NOT_ADMITTED -> {
                circuit.release();
                throw reject("not_admitted", ErrorType.CONFLICT, "아직 발급 순서가 아닙니다. 상태를 확인한 뒤 다시 요청해 주세요.");
            }
            case NO_PERMIT -> {
                circuit.release();
                throw reject("no_permit", ErrorType.TOO_MANY_REQUESTS, null);
            }
            case STALE_GENERATION -> {
                circuit.release();
                throw staleGeneration(campaignId);
            }
            case ACQUIRED -> { }
        }

        return issueHoldingPermit(campaignId, userId, token);
    }

    /** 본인 보유 목록 (§8-1). DB 만 쓰므로 gate·서킷과 무관하게 동작한다 (INV-W8-05). */
    public List<Coupon> myCoupons(Long userId) {
        requireIdentifiedUser(userId);
        return couponRepository.findAllByUserId(userId);
    }

    // ---- 관리자 (§7-3, §8-1). ROLE_ADMIN 검사가 접근 경계이므로 여기서는 신원을 다시 확인하지 않는다 ----

    /**
     * @param redisUnavailable true 면 Redis 값(세대 일치·대기열·승격·자리·접수 번호)을 읽지 못해 해당 항목이 비어 있다. gate 상태는 바꾸지 않는다
     */
    public record AdmissionStatusInfo(
        boolean open,
        CloseReason closeReason,
        Instant closedAt,
        Boolean generationMatches,
        CouponIssueCircuit.State dbCircuitState,
        Long waiting,
        Long admitted,
        Long permits,
        Long seq,
        int activeRequests,
        boolean autoReopenEnabled,
        int recentAutoReopens,
        boolean redisUnavailable
    ) { }

    public void openGate(Long campaignId) {
        requireCampaign(campaignId);
        gate.open(campaignId);
    }

    public void closeGate(Long campaignId) {
        requireCampaign(campaignId);
        gate.close(campaignId);
    }

    /** 읽기만 한다. Redis 예외가 나도 gate 상태를 바꾸지 않고 redisUnavailable 로 알린다. */
    public AdmissionStatusInfo admissionStatus(Long campaignId) {
        requireCampaign(campaignId);
        Optional<CouponAdmissionGate.Closure> closure = gate.closure(campaignId);
        boolean open = gate.isOpen(campaignId);
        CloseReason closeReason = closure.map(CouponAdmissionGate.Closure::reason).orElse(null);
        Instant closedAt = closure.map(CouponAdmissionGate.Closure::closedAt).orElse(null);
        boolean autoReopenEnabled = properties.autoReopen().enabled();
        int recentAutoReopens = gate.recentAutoReopens(campaignId);
        try {
            return new AdmissionStatusInfo(
                open, closeReason, closedAt,
                gate.generationMatches(campaignId),
                circuit.state(),
                queue.waitingCount(campaignId),
                queue.admittedCount(campaignId),
                semaphore.permitCount(campaignId),
                queue.acceptedCount(campaignId),
                metrics.activeRequests(),
                autoReopenEnabled, recentAutoReopens,
                false
            );
        } catch (DataAccessException e) {
            log.warn("관리자 상태 조회 중 Redis 호출 실패. Redis 값 없이 응답한다. campaignId={}", campaignId, e);
            return new AdmissionStatusInfo(
                open, closeReason, closedAt,
                null, circuit.state(), null, null, null, null,
                metrics.activeRequests(), autoReopenEnabled, recentAutoReopens,
                true
            );
        }
    }

    /**
     * 자리를 쥔 상태에서의 DB 발급. finally 가 자리 반납과 승격 제거를 맡는다.
     * settled 는 "업무 결과가 확정됐다"는 뜻이다. 발급 성공·기존 쿠폰·품절·종료는 확정이고, DB 일시 오류는 미확정이라 승격을 남긴다 (D15).
     */
    private ClaimInfo issueHoldingPermit(Long campaignId, Long userId, String token) {
        metrics.requestStarted();
        metrics.trackPermits(campaignId);
        ScheduledFuture<?> heartbeat = heartbeatScheduler.scheduleWithFixedDelay(
            () -> heartbeat(campaignId, token), properties.heartbeatInterval());
        boolean settled = false;
        try {
            IssueResult result = issueWithRetry(campaignId, userId);
            settled = true;
            circuit.recordSuccess();
            return new ClaimInfo(result.coupon().getId(), result.created());
        } catch (CoreException e) {
            settled = true;
            circuit.recordFailure(e);
            metrics.reject(e.getErrorType() == ErrorType.CONFLICT ? "sold_out" : "db_rejected");
            throw e;
        } catch (DataAccessException e) {
            circuit.recordFailure(e);
            log.warn("쿠폰 DB 발급이 일시 오류로 끝났다. 승격은 유지하고 503 으로 끝낸다. campaignId={} userId={}", campaignId, userId, e);
            throw reject("db_unavailable", ErrorType.SERVICE_UNAVAILABLE, "쿠폰 발급 저장소를 일시적으로 사용할 수 없습니다. 잠시 후 다시 시도해 주세요.");
        } finally {
            heartbeat.cancel(false);
            metrics.requestFinished();
            releasePermit(campaignId, token);
            if (settled) {
                removeAdmitted(campaignId, userId);
            }
        }
    }

    private IssueResult issueWithRetry(Long campaignId, Long userId) {
        for (int attempt = 0; ; attempt++) {
            try {
                return couponIssueService.issue(campaignId, userId);
            } catch (TransientDataAccessException e) {
                if (attempt >= DB_RETRIES) {
                    throw e;
                }
                log.warn("쿠폰 DB 발급 일시 오류. 재시도한다. attempt={} campaignId={} userId={}", attempt + 1, campaignId, userId, e);
            }
        }
    }

    private void heartbeat(Long campaignId, String token) {
        try {
            if (!semaphore.heartbeat(campaignId, token)) {
                log.warn("하트비트 갱신 대상 token 이 없다(이미 회수됨). campaignId={} token={}", campaignId, token);
            }
        } catch (RuntimeException e) {
            log.warn("하트비트 갱신 실패. 발급은 계속한다 (D4-2). campaignId={} token={}", campaignId, token, e);
        }
    }

    /** 자기 token 만 지운다 (INV-W8-04). 실패해도 DB 결과는 바꾸지 않는다 (INV-W8-07). */
    private void releasePermit(Long campaignId, String token) {
        for (int attempt = 0; ; attempt++) {
            try {
                boolean removed = semaphore.release(campaignId, token);
                if (!removed) {
                    log.warn("반납할 token 이 이미 회수됐다. campaignId={} token={}", campaignId, token);
                }
                metrics.release(removed ? "released" : "recovered");
                return;
            } catch (RuntimeException e) {
                if (attempt >= RELEASE_RETRIES) {
                    metrics.release("failed");
                    log.error("자리 반납이 {}회 재시도 후 실패했다. 하트비트 TTL 뒤 회수된다. campaignId={} token={}", RELEASE_RETRIES, campaignId, token, e);
                    return;
                }
            }
        }
    }

    private void removeAdmitted(Long campaignId, Long userId) {
        try {
            queue.removeAdmitted(campaignId, userId);
        } catch (RuntimeException e) {
            log.warn("승격 제거 실패. admitted TTL 뒤 정리된다. campaignId={} userId={}", campaignId, userId, e);
        }
    }

    /**
     * 상태를 바꾸는 요청 경로(접수·claim)의 Redis 호출. 예외는 REDIS_ERROR, 해석 불가 응답은 UNEXPECTED_RESULT 로 gate 를 닫고 503 으로 끝낸다 (§7-1).
     */
    private <T> T redis(Long campaignId, Supplier<T> call) {
        try {
            return call.get();
        } catch (DataAccessException e) {
            gate.forceClose(campaignId, CloseReason.REDIS_ERROR);
            log.warn("Redis 호출 실패. gate 를 닫는다. campaignId={}", campaignId, e);
            throw reject("redis_error", ErrorType.SERVICE_UNAVAILABLE, null);
        } catch (IllegalStateException e) {
            gate.forceClose(campaignId, CloseReason.UNEXPECTED_RESULT);
            log.error("Redis 응답을 해석할 수 없다. gate 를 닫는다. campaignId={}", campaignId, e);
            throw reject("unexpected_result", ErrorType.SERVICE_UNAVAILABLE, null);
        }
    }

    private CoreException staleGeneration(Long campaignId) {
        gate.forceClose(campaignId, CloseReason.STALE_GENERATION);
        return reject("stale_generation", ErrorType.SERVICE_UNAVAILABLE, null);
    }

    private String requireOpenGate(Long campaignId) {
        return gate.generation(campaignId)
            .orElseThrow(() -> reject("gate_closed", ErrorType.SERVICE_UNAVAILABLE, null));
    }

    private CoreException reject(String reason, ErrorType errorType, String message) {
        metrics.reject(reason);
        return new CoreException(errorType, message);
    }

    private long acceptLimitOf(CouponCampaign campaign) {
        return (long) Math.ceil(campaign.getTotalQuota() * properties.acceptLimitMultiplier());
    }

    private CouponCampaign requireCampaign(Long campaignId) {
        return couponCampaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "존재하지 않는 캠페인입니다."));
    }

    private static void requireActive(CouponCampaign campaign) {
        if (!campaign.isActive()) {
            throw new CoreException(ErrorType.CONFLICT, "종료된 캠페인입니다.");
        }
    }

    private void requireIdentifiedUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "사용자 식별에 실패했습니다.");
        }
    }
}
