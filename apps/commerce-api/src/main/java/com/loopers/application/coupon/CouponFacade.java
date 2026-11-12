package com.loopers.application.coupon;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponAdmissionRepository.Registration;
import com.loopers.domain.coupon.CouponAdmissionRepository.RegistrationOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Start;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponCampaignStatus;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.domain.coupon.OwnedCoupon;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * 쿠폰 대기 등록·조회·발급·입장 처리·보유 목록·수용 제어를 조율한다(설계 §6·§12.9~12.12).
 * 상위 TX 없이 짧은 읽기 TX → Redis 대기 상태 → 쓰기 TX → 필요한 후속 읽기 TX 순서로 실행해 Redis 를 기다리는 동안 DB 연결을 잡지 않는다.
 * 기존 DB 쿠폰은 모든 대기 조건보다 먼저 확인한다.
 *
 * <p>발급은 입장 때 확보한 READY 자리를 start 로 PROCESSING 으로 바꿔 그대로 쓴다. 자리를 다시 획득하지 않는다.
 * 각 TX 호출은 콜백 진입과 afterCompletion 통지를 요청 안에서 관찰해 업무 미시작·commit·rollback·종료 불명을 가른다.
 * 예외 종류보다 이 종료 판정을 먼저 적용한다. 종료 불명이 하나라도 있으면 PROCESSING 점유를 남기고 신규 수용을 닫는다.
 * 그 밖에는 모든 DB 호출이 끝난 뒤 finish 를 한 번 호출하며, 종료 기록 실패는 이미 정해진 업무 결과를 바꾸지 않는다.
 */
@Slf4j
@Component
public class CouponFacade {

    static final String UNIQUE_CAMPAIGN_USER = "uk_coupon_campaign_user";

    private final TransactionTemplate readTemplate;
    private final TransactionTemplate writeTemplate;
    private final CouponQueryRepository queryRepository;
    private final CouponCampaignRepository campaignRepository;
    private final CouponRepository couponRepository;
    private final ConstraintViolationClassifier classifier;
    private final CouponAdmissionService admission;

    public CouponFacade(
        PlatformTransactionManager transactionManager,
        CouponQueryRepository queryRepository,
        CouponCampaignRepository campaignRepository,
        CouponRepository couponRepository,
        ConstraintViolationClassifier classifier,
        CouponAdmissionService admission
    ) {
        this.readTemplate = new TransactionTemplate(transactionManager);
        this.readTemplate.setReadOnly(true);
        this.writeTemplate = new TransactionTemplate(transactionManager);
        this.queryRepository = queryRepository;
        this.campaignRepository = campaignRepository;
        this.couponRepository = couponRepository;
        this.classifier = classifier;
        this.admission = admission;
    }

    /** 4단위에서 Controller 가 새 issue 로 바뀌면 제거할 임시 호환. 헤더가 없으므로 기존 쿠폰만 반환하고 신규는 ID_REQUIRED 다. */
    public CouponIssueResult issue(Long userId, Long campaignId) {
        return issue(userId, campaignId, null).result();
    }

    /**
     * 기존 쿠폰 → 헤더 → 운영 캠페인·열림 → start 순서. STARTED 일 때만 DB 발급을 시작한다.
     * PROCESSING·ISSUED 관찰은 그사이 commit 됐을 수 있어 DB 를 한 번 다시 읽는다(업무 재실행 아님).
     */
    public CouponIssueOutcome issue(Long userId, Long campaignId, String waitingId) {
        Optional<OwnedCoupon> stored = findOwned(userId, campaignId);
        if (stored.isPresent()) {
            return CouponIssueOutcome.issued(CouponIssueResult.stored(stored.get()));
        }
        if (waitingId == null || waitingId.isBlank()) {
            throw new CoreException(ErrorType.COUPON_WAITING_ID_REQUIRED);
        }
        admission.requireAdmitting(campaignId);
        Start start = admission.start(campaignId, userId, waitingId);
        switch (start.outcome()) {
            case STARTED -> {
                return CouponIssueOutcome.issued(issueStarted(userId, campaignId, waitingId));
            }
            case PROCESSING -> {
                return findOwned(userId, campaignId)
                    .map(owned -> CouponIssueOutcome.issued(CouponIssueResult.stored(owned)))
                    .orElseGet(() -> CouponIssueOutcome.inProgress(start.snapshot()));
            }
            case ISSUED -> {
                OwnedCoupon owned = confirmIssued(userId, campaignId, waitingId, "발급 시작");
                return CouponIssueOutcome.issued(CouponIssueResult.stored(owned));
            }
            default -> throw new IllegalStateException("시작 결과를 처리할 수 없습니다. [outcome = " + start.outcome() + "]");
        }
    }

    /** 기존 쿠폰 → 운영 캠페인·열림 → 캠페인 없음 404/비활성·품절 409 → register. */
    public CouponWaitingResult registerWaiting(Long userId, Long campaignId) {
        Optional<OwnedCoupon> stored = findOwned(userId, campaignId);
        if (stored.isPresent()) {
            return new CouponWaitingResult(false, issuedSnapshot(campaignId, null, stored.get()));
        }
        admission.requireAdmitting(campaignId);
        readTemplate.executeWithoutResult(status -> requireIssuable(campaignId));
        Registration registration = admission.register(campaignId, userId);
        CouponWaitingSnapshot snapshot = registration.snapshot();
        if (snapshot.status() == CouponWaitingState.ISSUED) {
            OwnedCoupon owned = confirmIssued(userId, campaignId, snapshot.waitingId(), "등록");
            return new CouponWaitingResult(false, issuedSnapshot(campaignId, snapshot.waitingId(), owned));
        }
        return new CouponWaitingResult(registration.outcome() == RegistrationOutcome.CREATED, snapshot);
    }

    /** 기존 쿠폰 → 운영 캠페인 일치(닫힘은 막지 않음) → find. 상태를 바꾸지 않는다. */
    public CouponWaitingResult getWaiting(Long userId, Long campaignId) {
        Optional<OwnedCoupon> stored = findOwned(userId, campaignId);
        if (stored.isPresent()) {
            return new CouponWaitingResult(false, issuedSnapshot(campaignId, null, stored.get()));
        }
        admission.requireOperating(campaignId);
        CouponWaitingSnapshot snapshot = admission.find(campaignId, userId)
            .orElseThrow(() -> new CoreException(ErrorType.COUPON_WAITING_NOT_FOUND));
        if (snapshot.status() == CouponWaitingState.ISSUED) {
            OwnedCoupon owned = confirmIssued(userId, campaignId, snapshot.waitingId(), "조회");
            return new CouponWaitingResult(false, issuedSnapshot(campaignId, snapshot.waitingId(), owned));
        }
        return new CouponWaitingResult(false, snapshot);
    }

    /**
     * 운영 캠페인의 회수·승격 한 회차. 미설정·닫힘이면 DB·Redis 를 호출하지 않는다.
     * 캠페인 상태는 짧은 읽기 TX 안에서 값으로 복사한 뒤 TX 를 끝내고 Redis 를 호출한다. DB 는 쓰지 않는다.
     * 캠페인이 없거나 INACTIVE 면 회수만 한다.
     */
    public Optional<AdmissionProgress> processAdmissions() {
        Optional<Long> operating = admission.admittingCampaignId();
        if (operating.isEmpty()) {
            return Optional.empty();
        }
        CampaignAdmission state = readTemplate.execute(status -> campaignRepository.findById(operating.get())
            .map(c -> new CampaignAdmission(c.getStatus() == CouponCampaignStatus.ACTIVE, c.getIssuedCount() >= c.getTotalQuota()))
            .orElse(CampaignAdmission.MISSING));
        return admission.processAdmissions(state.soldOut(), state.active() && !state.soldOut());
    }

    /** STARTED 뒤의 DB 발급. 필요한 모든 DB 호출의 종료를 확인한 뒤에만 finish 한다. */
    private CouponIssueResult issueStarted(Long userId, Long campaignId, String waitingId) {
        boolean terminationUnknown = false;
        boolean writeCommitted = false;
        String unknownStage = null;
        CouponIssueResult result = null;
        RuntimeException error = null;
        try {
            TxCall<CouponIssueResult> write = call(writeTemplate, () -> issueInTransaction(userId, campaignId));
            Supplier<CouponIssueResult> followUp = null;
            if (write.returned) {
                result = write.candidate;
            } else if (write.unknown()) {
                terminationUnknown = true;
                unknownStage = "발급 쓰기 TX";
                error = unknownError(write);
            } else if (write.committed()) {
                writeCommitted = true;
                if (write.candidate != null) {
                    result = write.candidate;
                    log.warn("발급 commit 뒤 예외가 있었지만 확보한 결과를 유지합니다. [waitingId = {}, userId = {}, campaignId = {}, couponId = {}]",
                        waitingId, userId, campaignId, result.couponId(), write.failure);
                } else {
                    error = internal("발급 commit 은 확인했지만 응답 결과를 확보하지 못했습니다. 재요청으로 확인합니다.", write.failure);
                }
            } else if (!write.entered) {
                error = asRuntime(write.failure); // 업무 SQL 미시작. 자동 재시도하지 않는다.
            } else if (write.failure instanceof NotReserved) {
                followUp = () -> resolveNotReserved(userId, campaignId);
            } else if (classifier.namedUniqueViolation(write.failure).filter(UNIQUE_CAMPAIGN_USER::equals).isPresent()) {
                followUp = () -> storedAfterConflict(userId, campaignId);
            } else {
                error = asRuntime(write.failure);
            }

            if (followUp != null) {
                TxCall<CouponIssueResult> read = call(readTemplate, followUp);
                if (read.returned) {
                    result = read.candidate;
                } else if (read.unknown()) {
                    terminationUnknown = true;
                    unknownStage = "후속 읽기 TX";
                    error = unknownError(read);
                } else if (read.committed() && read.candidate != null) {
                    result = read.candidate;
                    log.warn("후속 읽기 종료 뒤 예외가 있었지만 확보한 결과를 유지합니다. [waitingId = {}, userId = {}, campaignId = {}]",
                        waitingId, userId, campaignId, read.failure);
                } else if (read.rolledBack() && read.failure instanceof CoreException rejection) {
                    error = rejection; // 404·409 등 정의한 업무 거절, 또는 원인 설명 불가 500
                } else {
                    error = asRuntime(read.failure); // 미시작 실패여도 앞선 쓰기 TX 의 rollback 확인은 유지된다.
                }
            }
        } catch (RuntimeException | Error e) {
            error = asRuntime(e);
        } finally {
            if (terminationUnknown) {
                admission.close();
                log.error("DB 종료를 확인하지 못해 PROCESSING 점유를 유지하고 신규 수용을 닫습니다. "
                    + "[waitingId = {}, userId = {}, campaignId = {}, stage = {}, result = 불명]", waitingId, userId, campaignId, unknownStage);
            } else {
                // 쿠폰 결과가 있거나 쓰기 commit 을 확인했으면 ISSUED(응답 결과를 못 얻은 commit 도 포함), 그 밖의 종료 확인된 실패는 FAILED.
                CouponWaitingState outcome = result != null || writeCommitted ? CouponWaitingState.ISSUED : CouponWaitingState.FAILED;
                if (!admission.finish(campaignId, userId, waitingId, outcome)) {
                    log.warn("대기 종료 기록이 적용되지 않았습니다. 업무 결과는 유지합니다. "
                        + "[waitingId = {}, userId = {}, campaignId = {}, outcome = {}, result = {}]",
                        waitingId, userId, campaignId, outcome, describe(result, error));
                }
            }
        }
        if (error != null) {
            throw error;
        }
        return result;
    }

    public PageResult<OwnedCoupon> getMyCoupons(Long userId, PageCommand page, ListSort sort) {
        return readTemplate.execute(status -> queryRepository.findOwnedPage(userId, page, sort));
    }

    public boolean status() {
        return admission.status();
    }

    public boolean close() {
        return admission.close();
    }

    /** 기본 token 자리 정리(설계 §8.3). 확장에서 제거 승인된 경로이며 4단위에서 API 와 함께 지운다. 대기실 키는 건드리지 않는다. */
    public long cleanup(List<String> tokens) {
        return admission.cleanup(tokens);
    }

    /** 운영 캠페인 미설정이면 409. 호출 전제: 운영자가 관련 실행·DB 종료를 확인했다. 시간 경과·PONG 으로 자동 호출하지 않는다. */
    public boolean open() {
        return admission.open();
    }

    /** 조건부 UPDATE 가 첫 업무 SQL 이다. INSERT 전 (campaign_id, user_id) 재조회와 캠페인 Entity 선행 로드를 하지 않는다. */
    private CouponIssueResult issueInTransaction(Long userId, Long campaignId) {
        int affected = campaignRepository.reserveOne(campaignId, ZonedDateTime.now());
        if (affected == 0) {
            throw new NotReserved();
        }
        if (affected != 1) {
            throw new IllegalStateException("수량 UPDATE 변경 행 수가 1 이 아닙니다. [campaignId = " + campaignId + ", affected = " + affected + "]");
        }
        CouponCampaignIssueData campaign = campaignRepository.findIssueData(campaignId)
            .orElseThrow(() -> new IllegalStateException("수량 UPDATE 뒤 캠페인을 읽지 못했습니다. [campaignId = " + campaignId + "]"));
        CouponModel saved = couponRepository.save(
            CouponModel.issue(userId, campaignId, campaign.benefitType(), campaign.benefitValue()));
        return new CouponIssueResult(saved.getId(), saved.getCampaignId(), campaign.name(),
            saved.getBenefitType(), saved.getBenefitValue(), true);
    }

    /** UPDATE 0 건의 원인을 새 읽기 TX 에서 판별한다. 실패 당시 상태의 재구성이 아니다. */
    private CouponIssueResult resolveNotReserved(Long userId, Long campaignId) {
        Optional<OwnedCoupon> owned = queryRepository.findOwned(userId, campaignId);
        if (owned.isPresent()) {
            return CouponIssueResult.stored(owned.get());
        }
        CouponCampaignModel campaign = campaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND));
        if (campaign.getStatus() == CouponCampaignStatus.INACTIVE) {
            throw new CoreException(ErrorType.COUPON_CAMPAIGN_INACTIVE);
        }
        if (campaign.getIssuedCount() >= campaign.getTotalQuota()) {
            throw new CoreException(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
        }
        throw new CoreException(ErrorType.INTERNAL_ERROR,
            "수량을 확보하지 못한 원인을 현재 상태로 설명할 수 없습니다. [campaignId = " + campaignId + "]");
    }

    private CouponIssueResult storedAfterConflict(Long userId, Long campaignId) {
        return queryRepository.findOwned(userId, campaignId)
            .map(CouponIssueResult::stored)
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR,
                "유일 제약 충돌 뒤 기존 쿠폰을 찾지 못했습니다. [userId = " + userId + ", campaignId = " + campaignId + "]"));
    }

    private Optional<OwnedCoupon> findOwned(Long userId, Long campaignId) {
        return readTemplate.execute(status -> queryRepository.findOwned(userId, campaignId));
    }

    /** Redis 의 ISSUED 를 DB 로 한 번 확인한다. 쿠폰이 없으면 설명할 수 없는 상태라 수용을 닫고 500 이다. */
    private OwnedCoupon confirmIssued(Long userId, Long campaignId, String waitingId, String stage) {
        Optional<OwnedCoupon> owned = findOwned(userId, campaignId);
        if (owned.isPresent()) {
            return owned.get();
        }
        admission.closeOnContradiction(stage, campaignId, userId, waitingId, "Redis ISSUED 인데 DB 쿠폰 없음");
        throw new CoreException(ErrorType.INTERNAL_ERROR, "대기 상태는 발급됨인데 쿠폰을 찾지 못했습니다.");
    }

    /** 등록 전 캠페인 확인. 없음 404, 비활성·품절 409. */
    private void requireIssuable(Long campaignId) {
        CouponCampaignModel campaign = campaignRepository.findById(campaignId)
            .orElseThrow(() -> new CoreException(ErrorType.COUPON_CAMPAIGN_NOT_FOUND));
        if (campaign.getStatus() == CouponCampaignStatus.INACTIVE) {
            throw new CoreException(ErrorType.COUPON_CAMPAIGN_INACTIVE);
        }
        if (campaign.getIssuedCount() >= campaign.getTotalQuota()) {
            throw new CoreException(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
        }
    }

    private static CouponWaitingSnapshot issuedSnapshot(Long campaignId, String waitingId, OwnedCoupon owned) {
        return new CouponWaitingSnapshot(campaignId, waitingId, CouponWaitingState.ISSUED, null, null, owned.couponId());
    }

    /** 콜백 첫 부분에서 진입을 표시하고 업무 SQL 전에 afterCompletion 을 등록한다. 결과는 콜백 안에서 복사한 값이다. */
    private static <T> TxCall<T> call(TransactionTemplate template, Supplier<T> work) {
        TxCall<T> call = new TxCall<>();
        try {
            template.execute(status -> {
                call.entered = true;
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int completionStatus) {
                        call.completion = completionStatus;
                    }
                });
                T value = work.get();
                call.candidate = value;
                return value;
            });
            call.returned = true;
        } catch (RuntimeException | Error e) {
            call.failure = e;
        }
        return call;
    }

    private static RuntimeException unknownError(TxCall<?> call) {
        return internal("DB TX 종료를 확인하지 못했습니다.", call.failure);
    }

    private static CoreException internal(String message, Throwable cause) {
        log.error(message, cause);
        return new CoreException(ErrorType.INTERNAL_ERROR, message);
    }

    private static RuntimeException asRuntime(Throwable failure) {
        if (failure instanceof RuntimeException runtime) {
            return runtime;
        }
        if (failure instanceof Error fatal) {
            throw fatal;
        }
        return new IllegalStateException(failure);
    }

    private static String describe(CouponIssueResult result, RuntimeException error) {
        if (result != null) {
            return (result.created() ? "CREATED" : "STORED") + " couponId " + result.couponId();
        }
        if (error instanceof CoreException core) {
            return "오류 " + core.getErrorType().getCode();
        }
        return error == null ? "불명" : "오류 " + error.getClass().getSimpleName();
    }

    /** 한 TX 호출의 요청 내부 관찰. 공유하지 않는다. */
    private static final class TxCall<T> {
        private boolean entered;
        private Integer completion;
        private boolean returned;
        private T candidate;
        private Throwable failure;

        /** 콜백에 들어간 뒤 UNKNOWN 이거나 완료 통지가 없으면 종료 불명이다. */
        boolean unknown() {
            return !returned && entered
                && (completion == null || completion == TransactionSynchronization.STATUS_UNKNOWN);
        }

        boolean committed() {
            return returned || (entered && completion != null && completion == TransactionSynchronization.STATUS_COMMITTED);
        }

        boolean rolledBack() {
            return !returned && entered && completion != null && completion == TransactionSynchronization.STATUS_ROLLED_BACK;
        }
    }

    /** 입장 처리용 캠페인 상태 복사본. 엔티티를 TX 밖으로 내보내지 않는다. */
    private record CampaignAdmission(boolean active, boolean soldOut) {
        static final CampaignAdmission MISSING = new CampaignAdmission(false, false);
    }

    /** 조건부 UPDATE 0 건. 쓰기 TX 를 rollback 으로 끝낸 뒤 새 읽기에서 원인을 판별하기 위한 내부 신호다. */
    private static final class NotReserved extends RuntimeException {
        NotReserved() {
            super("조건부 수량 UPDATE 0 건", null, false, false);
        }
    }
}
