package com.loopers.application.coupon;

import com.loopers.config.CouponAdmissionProperties;
import com.loopers.config.CouponWaitingProperties;
import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponAdmissionRepository.Acquisition;
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

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 신규 발급 수용의 열림/닫힘과 Redis 결과를 해석한다. 상태는 공유 accepting 값 하나이며 시작 시 닫혀 있다.
 * 관찰한 Redis 오류·불명·설명 불가 결과에서는 닫고, 다시 여는 것은 {@link #open()} 명시 호출뿐이다.
 *
 * <p>대기실(설계 §12.10~12.12): 운영 캠페인은 시작 설정의 campaignId 하나다. 미설정이면 열 수 없고 대기실은 비활성이다.
 * 정상 업무 결과(기존 항목·만료·미발견·대기·진행 중·품절·옛 종료)는 닫지 않는다. close 는 새 진입만 막고 진행 중 finish 는 계속 받는다.
 *
 * <p>acquire/release/cleanup 은 기본 token 자리 경로다. 대기실 발급은 이를 쓰지 않으며 4단위 정리까지 기존 제어·검사 컴파일용으로 남긴다.
 */
@Slf4j
@Component
public class CouponAdmissionService {

    private final CouponAdmissionRepository repository;
    private final int capacity;
    private final Long campaignId;
    private final long admissionDurationMillis;
    private final AtomicBoolean accepting = new AtomicBoolean(false);

    public CouponAdmissionService(CouponAdmissionRepository repository, CouponAdmissionProperties properties,
                                  CouponWaitingProperties waiting) {
        this.repository = repository;
        this.capacity = properties.capacity();
        this.campaignId = waiting.campaignId();
        this.admissionDurationMillis = waiting.admissionDuration().toMillis();
    }

    /**
     * 정상 반환이면 이 token 이 자리를 점유했다. 닫혀 있으면 Redis 를 호출하지 않는다.
     * FULL 은 429 이며 수용을 닫지 않는다. 예외·비정상 응답은 token 이 늦게 추가됐을 수 있으므로 닫고 503 이다.
     * 어느 예외에서도 호출자는 신규 DB 발급을 시작하거나 이 token 을 반환하지 않는다.
     */
    public void acquire(String token) {
        if (!accepting.get()) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
        Acquisition result;
        try {
            result = repository.tryAcquire(token, capacity);
        } catch (RuntimeException e) {
            closeAfterFailure("획득", token, "획득 여부 불명", e);
            throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
        }
        if (result == Acquisition.ACQUIRED) {
            return;
        }
        if (result == Acquisition.FULL) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_FULL);
        }
        closeAfterFailure("획득", token, "획득 여부 불명(결과 " + result + ")", null);
        throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    /**
     * ACQUIRED 를 확인한 실행의 첫 반환. 업무 결과가 정해진 뒤 호출되므로 예외를 던지지 않는다.
     * 1 이 아니면(0 = 있어야 할 점유가 없음, 예외 = 반환 여부 불명) 로그를 남기고 닫는다. 업무 결과는 호출자가 그대로 유지한다.
     * 반환값은 정상 반환(1) 여부이며, 호출자가 사용자·캠페인·업무 결과를 같은 token 으로 이어 기록할 때 쓴다.
     */
    public boolean release(String token) {
        long removed;
        try {
            removed = repository.release(token);
        } catch (RuntimeException e) {
            closeAfterFailure("반환", token, "반환 여부 불명", e);
            return false;
        }
        if (removed != 1) {
            closeAfterFailure("반환", token, "정상 첫 반환에서 점유 없음(removed " + removed + ")", null);
            return false;
        }
        return true;
    }

    public boolean status() {
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
        if (campaignId == null) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED,
                "운영 캠페인(commerce.coupon.waiting.campaign-id)이 설정되지 않아 수용을 열 수 없습니다.");
        }
        accepting.set(true);
        return accepting.get();
    }

    /**
     * 닫힌 상태에서 지정한 token 만 하나씩 제거하고 제거 수를 돌려준다. 이미 없는 token 의 0 은 정상이다.
     * 도중에 Redis 가 실패하면 앞서 제거한 것은 되돌리지 않고, 닫힘을 유지한 채 503 이다. 남은 token 은 재조회로 다시 확인한다.
     */
    public long cleanup(List<String> tokens) {
        if (accepting.get()) {
            throw new CoreException(ErrorType.COUPON_ADMISSION_OPERATION_REJECTED);
        }
        if (tokens == null || tokens.isEmpty() || tokens.stream().anyMatch(t -> t == null || t.isBlank())) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "정리할 token 은 비어 있지 않은 문자열 목록이어야 합니다.");
        }
        long removed = 0;
        for (String token : tokens) {
            try {
                removed += repository.release(token);
            } catch (RuntimeException e) {
                closeAfterFailure("정리", token, "이 token 의 제거 여부 불명, 앞선 token 제거 수 " + removed, e);
                throw new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
            }
        }
        return removed;
    }

    // ---- 대기실 ----

    /** 요청 캠페인이 설정된 운영 캠페인이어야 한다(닫힘은 보지 않음). 아니면 Redis 를 호출하지 않고 503 이다. 조회용이다. */
    public void requireOperating(Long requested) {
        if (campaignId == null || !campaignId.equals(requested)) {
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
        return campaignId != null && accepting.get() ? Optional.of(campaignId) : Optional.empty();
    }

    /** CREATED/EXISTING 만 돌려준다. Redis 품절은 409, 실패·INVARIANT 는 닫고 503 이다. 새 waitingId 후보는 서버가 만든다. */
    public Registration register(Long campaign, Long userId) {
        Registration result;
        try {
            result = repository.register(campaign, userId, UUID.randomUUID().toString());
        } catch (RuntimeException e) {
            throw unavailable("등록", campaign, userId, null, "등록 결과 불명", e);
        }
        if (result == null) {
            throw unavailable("등록", campaign, userId, null, "응답 없음", null);
        }
        return switch (result.outcome()) {
            case CREATED, EXISTING -> result;
            case SOLD_OUT -> throw new CoreException(ErrorType.COUPON_CAMPAIGN_SOLD_OUT);
            case INVARIANT_ERROR -> throw unavailable("등록", campaign, userId, null, "INVARIANT_ERROR", null);
        };
    }

    /** 상태를 바꾸지 않는 조회. Redis 실패·설명 불가 상태도 관찰한 오류이므로 닫고 503 이다. */
    public Optional<CouponWaitingSnapshot> find(Long campaign, Long userId) {
        Optional<CouponWaitingSnapshot> result;
        try {
            result = repository.find(campaign, userId);
        } catch (RuntimeException e) {
            throw unavailable("조회", campaign, userId, null, "조회 실패", e);
        }
        if (result == null) {
            throw unavailable("조회", campaign, userId, null, "응답 없음", null);
        }
        return result;
    }

    /**
     * STARTED·PROCESSING·ISSUED 만 돌려준다. 정상 거절은 404/409 이며 닫지 않는다. 실패·INVARIANT 는 닫고 503 이다.
     * 예외로 끝나면 호출자는 DB 발급을 시작하지 않는다.
     */
    public Start start(Long campaign, Long userId, String waitingId) {
        Start result;
        try {
            result = repository.start(campaign, userId, waitingId);
        } catch (RuntimeException e) {
            throw unavailable("시작", campaign, userId, waitingId, "시작 여부 불명", e);
        }
        if (result == null) {
            throw unavailable("시작", campaign, userId, waitingId, "응답 없음", null);
        }
        return switch (result.outcome()) {
            case STARTED, PROCESSING, ISSUED -> result;
            case NOT_FOUND -> throw new CoreException(ErrorType.COUPON_WAITING_NOT_FOUND);
            case NOT_READY -> throw new CoreException(ErrorType.COUPON_WAITING_NOT_READY);
            case EXPIRED -> throw new CoreException(ErrorType.COUPON_WAITING_EXPIRED);
            case ALREADY_USED -> throw new CoreException(ErrorType.COUPON_WAITING_ALREADY_USED);
            case INVARIANT_ERROR -> throw unavailable("시작", campaign, userId, waitingId, "INVARIANT_ERROR", null);
        };
    }

    /**
     * DB 종료를 확인한 실행의 종료 기록. 업무 결과가 정해진 뒤 호출되므로 예외를 던지지 않고 적용 여부만 돌려준다.
     * UNCHANGED 는 WARN 만 남기고 수용을 유지한다. 실패·INVARIANT 는 닫는다. 권한 복원·재시도는 없다.
     */
    public boolean finish(Long campaign, Long userId, String waitingId, CouponWaitingState outcome) {
        FinishOutcome result;
        try {
            result = repository.finish(campaign, userId, waitingId, outcome);
        } catch (RuntimeException e) {
            closeWaiting("종료", campaign, userId, waitingId, "종료 기록 여부 불명(결과 " + outcome + ")", e);
            return false;
        }
        if (result == FinishOutcome.APPLIED) {
            return true;
        }
        if (result == FinishOutcome.UNCHANGED) {
            log.warn("쿠폰 대기 종료 기록이 현재 항목과 맞지 않아 바꾸지 않았습니다. [campaignId = {}, userId = {}, waitingId = {}, outcome = {}]",
                campaign, userId, waitingId, outcome);
            return false;
        }
        closeWaiting("종료", campaign, userId, waitingId, "종료 기록 " + result + "(결과 " + outcome + ")", null);
        return false;
    }

    /** 운영 캠페인의 회수·승격. 미설정·닫힘이면 비어 있다. 실패·INVARIANT 는 닫고 503 이다. */
    public Optional<AdmissionProgress> processAdmissions(boolean soldOut, boolean canAdmit) {
        Optional<Long> campaign = admittingCampaignId();
        if (campaign.isEmpty()) {
            return Optional.empty();
        }
        AdmissionProgress progress;
        try {
            progress = repository.processAdmissions(campaign.get(), capacity, admissionDurationMillis, soldOut, canAdmit);
        } catch (RuntimeException e) {
            throw unavailable("입장 처리", campaign.get(), null, null, "회수·승격 결과 불명", e);
        }
        return Optional.of(Objects.requireNonNull(progress, "입장 처리 응답이 없습니다."));
    }

    /** Redis 상태와 DB 결과의 모순처럼 Facade 가 발견한 설명 불가 상태. 닫고 로그만 남긴다. */
    public void closeOnContradiction(String stage, Long campaign, Long userId, String waitingId, String detail) {
        closeWaiting(stage, campaign, userId, waitingId, detail, null);
    }

    private CoreException unavailable(String stage, Long campaign, Long userId, String waitingId, String outcome, Exception cause) {
        closeWaiting(stage, campaign, userId, waitingId, outcome, cause);
        return new CoreException(ErrorType.COUPON_ADMISSION_UNAVAILABLE);
    }

    private void closeWaiting(String stage, Long campaign, Long userId, String waitingId, String outcome, Exception cause) {
        accepting.set(false);
        log.error("쿠폰 신규 발급 수용을 닫습니다. [stage = {}, campaignId = {}, userId = {}, waitingId = {}, outcome = {}]",
            stage, campaign, userId, waitingId, outcome, cause);
    }

    /** token 이 남았거나 기대한 점유가 사라졌을 수 있는 경로. 수동 확인에서 요청 결과와 연결할 수 있도록 token·단계·확인된 결과를 남긴다. */
    private void closeAfterFailure(String stage, String token, String outcome, Exception cause) {
        accepting.set(false);
        log.error("쿠폰 신규 발급 수용을 닫습니다. [stage = {}, token = {}, outcome = {}]", stage, token, outcome, cause);
    }
}
