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
 * 신규 발급 수용의 열림/닫힘과 대기실 Redis 결과를 해석한다. 상태는 공유 accepting 값 하나이며 시작 시 닫혀 있다.
 * 관찰한 Redis 오류·불명·설명 불가 결과에서는 닫고, 다시 여는 것은 {@link #open()} 명시 호출뿐이다.
 *
 * <p>대기실(설계 §12.10~12.12): 운영 캠페인은 시작 설정의 campaignId 하나다. 미설정이면 열 수 없고 대기실은 비활성이다.
 * 정상 업무 결과(기존 항목·만료·미발견·대기·진행 중·품절·옛 종료)는 닫지 않는다. close 는 새 진입만 막고 진행 중 finish 는 계속 받는다.
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

    /** 반복 가능. 진행 중인 실행을 취소하거나 완료를 기다리지 않는다. */
    public boolean close() {
        accepting.set(false);
        return accepting.get();
    }

    /**
     * 운영 캠페인이 설정돼 있어야 연다. 미설정이면 409 다.
     * 호출 전제: 운영자가 관련 실행·DB 종료와 대기 상태를 확인했다. 이 메서드는 그 확인을 검사하지 않는다.
     */
    public boolean open() {
        if (operatingCampaignId == null) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED,
                "운영 캠페인(commerce.coupon.waiting.campaign-id)이 설정되지 않아 수용을 열 수 없습니다.");
        }
        accepting.set(true);
        return accepting.get();
    }

    /** 요청 캠페인이 설정된 운영 캠페인이어야 한다(닫힘은 보지 않음). 아니면 Redis 를 호출하지 않고 503 이다. 조회용이다. */
    public void requireOperating(Long requested) {
        if (operatingCampaignId == null || !operatingCampaignId.equals(requested)) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
    }

    /** 운영 캠페인 일치 + 수용 열림. 신규 등록·발급 시작용이다. */
    public void requireAdmitting(Long requested) {
        requireOperating(requested);
        if (!accepting.get()) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
    }

    /** 입장 처리를 해도 되면 운영 캠페인 ID. 미설정·닫힘이면 비어 있고 호출자는 DB·Redis 를 호출하지 않는다. */
    public Optional<Long> admittingCampaignId() {
        return operatingCampaignId != null && accepting.get() ? Optional.of(operatingCampaignId) : Optional.empty();
    }

    /** CREATED/EXISTING 만 돌려준다. Redis 품절은 409, 실패·INVARIANT 는 닫고 503 이다. 새 waitingId 후보는 서버가 만든다. */
    public Registration register(Long campaignId, Long userId) {
        Registration result = callRedis("등록", campaignId, userId, null, "등록 결과 불명",
            () -> repository.register(campaignId, userId, UUID.randomUUID().toString()));
        return switch (result.outcome()) {
            case CREATED, EXISTING -> result;
            case SOLD_OUT -> throw new CoreException(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
            case INVARIANT_ERROR -> throw unavailable("등록", campaignId, userId, null, "INVARIANT_ERROR", null);
        };
    }

    /** 상태를 바꾸지 않는 조회. Redis 실패·설명 불가 상태도 관찰한 오류이므로 닫고 503 이다. */
    public Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId) {
        return callRedis("조회", campaignId, userId, null, "조회 실패", () -> repository.find(campaignId, userId));
    }

    /**
     * STARTED·PROCESSING·ISSUED 만 돌려준다. 정상 거절은 404/409 이며 닫지 않는다. 실패·INVARIANT 는 닫고 503 이다.
     * 예외로 끝나면 호출자는 DB 발급을 시작하지 않는다.
     */
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

    /**
     * DB 종료를 확인한 실행의 종료 기록. 업무 결과가 정해진 뒤 호출되므로 예외를 던지지 않고 적용 여부만 돌려준다.
     * UNCHANGED 는 WARN 만 남기고 수용을 유지한다. 실패·INVARIANT 는 닫는다. 권한 복원·재시도는 없다.
     */
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

    /** 운영 캠페인의 회수·승격. 미설정·닫힘이면 비어 있다. 실패·INVARIANT 는 닫고 503 이다. */
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

    /** Redis 상태와 DB 결과의 모순처럼 Facade 가 발견한 설명 불가 상태. 닫고 로그만 남긴다. */
    public void closeOnContradiction(String stage, Long campaignId, Long userId, String waitingId, String detail) {
        closeWaiting(stage, campaignId, userId, waitingId, detail, null);
    }

    /** Redis 호출의 예외(적용 여부 불명)와 null 응답을 관찰한 오류로 보고 수용을 닫은 뒤 503 으로 바꾼다. */
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
