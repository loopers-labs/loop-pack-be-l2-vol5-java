package com.loopers.application.coupon;

import com.loopers.domain.common.ListSort;
import com.loopers.domain.common.PageCommand;
import com.loopers.domain.common.PageResult;
import com.loopers.domain.coupon.CouponCampaignIssueData;
import com.loopers.domain.coupon.CouponCampaignModel;
import com.loopers.domain.coupon.CouponCampaignRepository;
import com.loopers.domain.coupon.CouponCampaignStatus;
import com.loopers.domain.coupon.CouponModel;
import com.loopers.domain.coupon.CouponQueryRepository;
import com.loopers.domain.coupon.CouponRepository;
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
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 쿠폰 발급·보유 목록·수용 제어를 조율한다. 상위 TX 없이 사전 읽기 TX → Redis 자리 획득 → 쓰기 TX → 필요한 후속 읽기 TX 순서로 실행해
 * Redis 를 기다리는 동안 DB 연결을 잡지 않는다.
 *
 * <p>각 TX 호출은 콜백 진입과 afterCompletion 통지를 요청 안에서 관찰해 업무 미시작·commit·rollback·종료 불명을 가른다.
 * 예외 종류보다 이 종료 판정을 먼저 적용한다. 종료 불명이 하나라도 있으면 자기 token 을 남기고 신규 수용을 닫는다.
 * 그 밖에는 모든 DB 호출이 끝난 뒤 token 을 한 번 반환하며, 반환 실패는 이미 정해진 업무 결과를 바꾸지 않는다.
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

    public CouponIssueResult issue(Long userId, Long campaignId) {
        // 사전 읽기 실패는 그대로 전파한다. 아직 자리를 잡지 않았다.
        Optional<OwnedCoupon> stored = readTemplate.execute(status -> queryRepository.findOwned(userId, campaignId));
        if (stored.isPresent()) {
            return CouponIssueResult.stored(stored.get());
        }

        String token = UUID.randomUUID().toString();
        try {
            admission.acquire(token);
        } catch (CoreException e) {
            if (e.getErrorType() == ErrorType.COUPON_ADMISSION_UNAVAILABLE) {
                log.warn("쿠폰 신규 수용 불가(닫힘 또는 획득 실패·불명). 신규 DB 발급을 시작하지 않습니다. "
                    + "[token = {}, userId = {}, campaignId = {}]", token, userId, campaignId);
            }
            throw e;
        }

        boolean terminationUnknown = false;
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
                if (write.candidate != null) {
                    result = write.candidate;
                    log.warn("발급 commit 뒤 예외가 있었지만 확보한 결과를 유지합니다. [token = {}, userId = {}, campaignId = {}, couponId = {}]",
                        token, userId, campaignId, result.couponId(), write.failure);
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
                    log.warn("후속 읽기 종료 뒤 예외가 있었지만 확보한 결과를 유지합니다. [token = {}, userId = {}, campaignId = {}]",
                        token, userId, campaignId, read.failure);
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
                log.error("DB 종료를 확인하지 못해 token 을 유지하고 신규 수용을 닫습니다. "
                    + "[token = {}, userId = {}, campaignId = {}, stage = {}, result = 불명]", token, userId, campaignId, unknownStage);
            } else if (!admission.release(token)) {
                log.error("token 반환이 정상 완료되지 않아 수용을 닫았습니다. 업무 결과는 유지합니다. "
                    + "[token = {}, userId = {}, campaignId = {}, result = {}]", token, userId, campaignId, describe(result, error));
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

    /** 호출 전제: 운영자가 관련 실행·DB 종료와 대상 token 을 수동으로 확인했다(설계 §8.3). 이 메서드는 그 확인을 검사하지 않는다. */
    public long cleanup(List<String> tokens) {
        return admission.cleanup(tokens);
    }

    /** 호출 전제는 cleanup 과 같다. 시간 경과·빈 Set·PONG 으로 자동 호출하지 않는다. */
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

    /** 조건부 UPDATE 0 건. 쓰기 TX 를 rollback 으로 끝낸 뒤 새 읽기에서 원인을 판별하기 위한 내부 신호다. */
    private static final class NotReserved extends RuntimeException {
        NotReserved() {
            super("조건부 수량 UPDATE 0 건", null, false, false);
        }
    }
}
