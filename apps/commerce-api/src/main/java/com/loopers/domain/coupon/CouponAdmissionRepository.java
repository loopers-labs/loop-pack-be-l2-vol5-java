package com.loopers.domain.coupon;

import java.util.Optional;

/**
 * 캠페인별 대기 상태 저장소. 실행 자리는 READY + PROCESSING 수로 센다.
 * Redis 오류·timeout 은 예외로 전달하며, 예외는 명령이 적용되지 않았다는 뜻이 아니다.
 * INVARIANT_ERROR 는 저장 상태를 설명할 수 없어 아무것도 쓰지 않았다는 뜻이다.
 */
public interface CouponAdmissionRepository {

    /**
     * 항목이 없거나 EXPIRED/FAILED 일 때만 맨 뒤에 새로 등록한다(CREATED). 그 밖에는 현재 항목을 EXISTING 으로 돌려준다.
     * 기한 지난 READY 는 회수하지 않고 EXPIRED 로 보여 주기만 한다.
     */
    Registration register(Long campaignId, Long userId, String newWaitingId);

    /** 상태를 바꾸지 않는다. 품절이면 WAITING/READY 를 SOLD_OUT 으로 보여 준다(PROCESSING 은 그대로). */
    Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId);

    /**
     * PROCESSING 은 DB 종료 확인 전이라 기한이 지나도 회수하지 않는다.
     * soldOut·canAdmit 은 호출자가 DB 에서 읽은 값이다.
     */
    AdmissionProgress processAdmissions(Long campaignId, int capacity, long admissionDurationMillis, boolean soldOut, boolean canAdmit);

    /** 같은 waitingId 의 기한 전 READY 일 때만 PROCESSING 으로 바꾼다(STARTED). */
    Start start(Long campaignId, Long userId, String waitingId);

    /** 자기 PROCESSING 점유만 반환한다. 현재 waitingId 의 PROCESSING 이 아니면 UNCHANGED 다. */
    FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome);

    enum RegistrationOutcome {
        CREATED,
        EXISTING,
        SOLD_OUT,
        INVARIANT_ERROR
    }

    /** SOLD_OUT·INVARIANT_ERROR 이면 snapshot 은 null 이다. */
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

    /** PROCESSING·ISSUED 일 때만 snapshot 이 있다. */
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
