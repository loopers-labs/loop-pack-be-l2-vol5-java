package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.AdmissionProgress;
import com.loopers.domain.coupon.CouponAdmissionRepository.FinishOutcome;
import com.loopers.domain.coupon.CouponAdmissionRepository.Registration;
import com.loopers.domain.coupon.CouponAdmissionRepository.Start;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * 신규 발급 수용의 열림/닫힘을 갖고 대기실 Redis 결과를 업무 결과로 바꾼다.
 * Redis 오류·설명할 수 없는 결과를 보면 닫고, 다시 여는 것은 {@link #open()} 명시 호출뿐이다.
 */
@Slf4j
@Component
public class CouponAdmissionService {

    private final CouponAdmissionRepository repository;
    private final int capacity;
    private final Long operatingCampaignId;
    private final long admissionDurationMillis;
    private final AtomicBoolean accepting = new AtomicBoolean(false);

    public CouponAdmissionService(CouponAdmissionRepository repository, CouponAdmissionProperties properties,
                                  CouponWaitingProperties waiting) {
        this.repository = repository;
        this.capacity = properties.capacity();
        this.operatingCampaignId = waiting.campaignId();
        this.admissionDurationMillis = waiting.admissionDuration().toMillis();
    }

    public boolean isAccepting() {
        return accepting.get();
    }

    /** 새 진입만 막는다. 진행 중인 실행을 취소하거나 기다리지 않는다. */
    public boolean close() {
        accepting.set(false);
        return accepting.get();
    }

    /** 호출 전제: 운영자가 진행 중 실행과 DB 종료를 확인했다. 이 메서드는 그 확인을 검사하지 않는다. */
    public boolean open() {
        if (operatingCampaignId == null) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED,
                "운영 캠페인(commerce.coupon.waiting.campaign-id)이 설정되지 않아 수용을 열 수 없습니다.");
        }
        accepting.set(true);
        return accepting.get();
    }

    /** 조회용이라 수용 닫힘은 막지 않는다. */
    public void requireOperating(Long requested) {
        if (operatingCampaignId == null || !operatingCampaignId.equals(requested)) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
    }

    public void requireAdmitting(Long requested) {
        requireOperating(requested);
        if (!accepting.get()) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
    }

    public Optional<Long> admittingCampaignId() {
        return operatingCampaignId != null && accepting.get() ? Optional.of(operatingCampaignId) : Optional.empty();
    }

    public Registration register(Long campaignId, Long userId) {
        Registration result = callRedis("등록", campaignId, userId, null, "등록 결과 불명",
            () -> repository.register(campaignId, userId, UUID.randomUUID().toString()));
        return switch (result.outcome()) {
            case CREATED, EXISTING -> result;
            case SOLD_OUT -> throw new CoreException(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
            case INVARIANT_ERROR -> throw unavailable("등록", campaignId, userId, null, "INVARIANT_ERROR", null);
        };
    }

    /** 조회여도 Redis 실패는 관찰한 오류이므로 수용을 닫는다. */
    public Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId) {
        return callRedis("조회", campaignId, userId, null, "조회 실패", () -> repository.find(campaignId, userId));
    }

    /** 정상 거절(404·409)은 수용을 닫지 않는다. */
    public Start start(Long campaignId, Long userId, String waitingId) {
        Start result = callRedis("시작", campaignId, userId, waitingId, "시작 여부 불명",
            () -> repository.start(campaignId, userId, waitingId));
        return switch (result.outcome()) {
            case STARTED, PROCESSING, ISSUED -> result;
            case NOT_FOUND -> throw new CoreException(ErrorType.COUPON_WAITING_NOT_FOUND);
            case NOT_READY -> throw new CoreException(ErrorType.COUPON_WAITING_NOT_READY);
            case EXPIRED -> throw new CoreException(ErrorType.COUPON_WAITING_EXPIRED);
            case ALREADY_USED -> throw new CoreException(ErrorType.COUPON_WAITING_ALREADY_USED);
            case INVARIANT_ERROR -> throw unavailable("시작", campaignId, userId, waitingId, "INVARIANT_ERROR", null);
        };
    }

    /** 업무 결과가 정해진 뒤 호출되므로 예외를 던지지 않는다. 권한 복원·재시도는 하지 않는다. */
    public boolean finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome) {
        FinishOutcome result;
        try {
            result = repository.finish(campaignId, userId, waitingId, outcome);
        } catch (RuntimeException e) {
            closeWaiting("종료", campaignId, userId, waitingId, "종료 기록 여부 불명(결과 " + outcome + ")", e);
            return false;
        }
        if (result == FinishOutcome.APPLIED) {
            return true;
        }
        if (result == FinishOutcome.UNCHANGED) {
            log.warn("쿠폰 대기 종료 기록이 현재 항목과 맞지 않아 바꾸지 않았습니다. [campaignId = {}, userId = {}, waitingId = {}, outcome = {}]",
                campaignId, userId, waitingId, outcome);
            return false;
        }
        closeWaiting("종료", campaignId, userId, waitingId, "종료 기록 " + result + "(결과 " + outcome + ")", null);
        return false;
    }

    public Optional<AdmissionProgress> processAdmissions(boolean soldOut, boolean canAdmit) {
        Optional<Long> operating = admittingCampaignId();
        if (operating.isEmpty()) {
            return Optional.empty();
        }
        AdmissionProgress progress;
        try {
            progress = repository.processAdmissions(operating.get(), capacity, admissionDurationMillis, soldOut, canAdmit);
        } catch (RuntimeException e) {
            throw unavailable("입장 처리", operating.get(), null, null, "회수·승격 결과 불명", e);
        }
        return Optional.of(Objects.requireNonNull(progress, "입장 처리 응답이 없습니다."));
    }

    public void closeOnContradiction(String stage, Long campaignId, Long userId, String waitingId, String detail) {
        closeWaiting(stage, campaignId, userId, waitingId, detail, null);
    }

    /** 예외는 명령이 적용되지 않았다는 뜻이 아니므로 결과를 모르는 것으로 보고 수용을 닫는다. */
    private <T> T callRedis(String stage, Long campaignId, Long userId, String waitingId, String unknownOutcome, Supplier<T> call) {
        T result;
        try {
            result = call.get();
        } catch (RuntimeException e) {
            throw unavailable(stage, campaignId, userId, waitingId, unknownOutcome, e);
        }
        if (result == null) {
            throw unavailable(stage, campaignId, userId, waitingId, "응답 없음", null);
        }
        return result;
    }

    private CoreException unavailable(String stage, Long campaignId, Long userId, String waitingId, String outcome, Exception cause) {
        closeWaiting(stage, campaignId, userId, waitingId, outcome, cause);
        return new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    private void closeWaiting(String stage, Long campaignId, Long userId, String waitingId, String outcome, Exception cause) {
        accepting.set(false);
        log.error("쿠폰 신규 발급 수용을 닫습니다. [stage = {}, campaignId = {}, userId = {}, waitingId = {}, outcome = {}]",
            stage, campaignId, userId, waitingId, outcome, cause);
    }
}
