package com.loopers.domain.coupon;

import java.util.Optional;

/**
 * 쿠폰 입장 대기 상태의 저장소(설계 §12.8·12.11~12.12). 캠페인별 대기·입장(READY)·실행(PROCESSING) 상태를 원자적으로 바꾸며
 * 실행 자리는 READY+PROCESSING 수로 센다.
 * Redis 오류·timeout·비정상 응답은 결과값이 아니라 예외로 전달한다. 예외는 명령이 적용되지 않았다는 뜻이 아니다.
 *
 * <p>결과의 INVARIANT_ERROR 는 저장 상태를 설명할 수 없어 아무것도 쓰지 않았다는 뜻이다. 수용을 닫을지는 호출자가 판단한다.
 */
public interface CouponAdmissionRepository {

    /**
     * 유효 항목(WAITING·기한 전 READY·PROCESSING·ISSUED)은 그대로 EXISTING 이다. 기한이 지났지만 회수 전인 READY 는
     * 저장·회수 없이 EXPIRED 표현으로 EXISTING 이다. 항목이 없거나 저장 상태가 EXPIRED/FAILED 일 때만, 품절 표시가 없으면
     * newWaitingId 와 새 순번으로 맨 뒤에 등록한다(CREATED). 등록에서 승격·회수는 하지 않는다.
     */
    Registration register(Long campaignId, Long userId, String newWaitingId);

    /**
     * 현재 항목을 상태 변경 없이 읽는다. 품절 표시가 있으면 WAITING/READY 는 SOLD_OUT, 그 밖의 기한 지난 READY 는 EXPIRED 로 보여 준다.
     * PROCESSING 은 품절이어도 그대로다. 저장 상태를 설명할 수 없으면 예외다.
     */
    Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId);

    /**
     * 기한 지난 READY 회수 → 빈자리만큼 queue 앞순번을 READY 로 승격한다. PROCESSING 은 회수하지 않는다.
     * soldOut·canAdmit 은 호출자가 DB 에서 읽은 값이다. soldOut 이면 품절 표시를 켜고(끄지 않음), soldOut 이거나 !canAdmit 이면 승격하지 않는다.
     * 저장 상태를 설명할 수 없으면 쓰지 않고 예외다.
     */
    AdmissionProgress processAdmissions(Long campaignId, int capacity, long admissionDurationMillis, boolean soldOut, boolean canAdmit);

    /** 현재 항목의 waitingId 가 같고 기한 전 READY 일 때만 PROCESSING 으로 바꾼다(STARTED). 기한 연장·품절 검사·회수는 하지 않는다. */
    Start start(Long campaignId, Long userId, String waitingId);

    /**
     * 호출자가 DB 종료를 확인한 실행의 결과(ISSUED 또는 FAILED)를 기록하고 자기 PROCESSING 점유만 반환한다.
     * 현재 waitingId·PROCESSING 이 아니면 UNCHANGED(무변경)이고, 맞는데 점유가 없으면 INVARIANT_ERROR 다.
     */
    FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome);

    enum RegistrationOutcome {
        CREATED,
        EXISTING,
        SOLD_OUT,
        INVARIANT_ERROR
    }

    /** CREATED/EXISTING 이면 snapshot 이 있고, SOLD_OUT/INVARIANT_ERROR 이면 null 이다. */
    record Registration(RegistrationOutcome outcome, CouponWaitingSnapshot snapshot) {

        public Registration {
            boolean hasSnapshot = outcome == RegistrationOutcome.CREATED || outcome == RegistrationOutcome.EXISTING;
            if (outcome == null || (snapshot != null) != hasSnapshot) {
                throw new IllegalArgumentException("등록 결과와 snapshot 이 맞지 않습니다. [outcome = " + outcome + ", snapshot = " + snapshot + "]");
            }
        }
    }

    record AdmissionProgress(long expired, long admitted) {

        public AdmissionProgress {
            if (expired < 0 || admitted < 0) {
                throw new IllegalArgumentException("회수·승격 수는 0 이상이어야 합니다. [expired = " + expired + ", admitted = " + admitted + "]");
            }
        }
    }

    enum StartOutcome {
        STARTED,
        PROCESSING,
        ISSUED,
        NOT_FOUND,
        NOT_READY,
        EXPIRED,
        ALREADY_USED,
        INVARIANT_ERROR
    }

    /** PROCESSING/ISSUED 이면 현재 snapshot 이 있고, 그 밖에는 null 이다. */
    record Start(StartOutcome outcome, CouponWaitingSnapshot snapshot) {

        public Start {
            boolean hasSnapshot = outcome == StartOutcome.PROCESSING || outcome == StartOutcome.ISSUED;
            if (outcome == null || (snapshot != null) != hasSnapshot) {
                throw new IllegalArgumentException("시작 결과와 snapshot 이 맞지 않습니다. [outcome = " + outcome + ", snapshot = " + snapshot + "]");
            }
        }
    }

    enum FinishOutcome {
        APPLIED,
        UNCHANGED,
        INVARIANT_ERROR
    }
}
