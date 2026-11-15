package com.loopers.application.coupon;

/** Redis 판단과 DB 최종 판단의 발급 거절 문구. */
final class CouponRejections {
    static final String SOLD_OUT = "쿠폰 발급 한도가 소진되었습니다.";
    static final String ENDED = "종료된 쿠폰 행사입니다.";
    /** 서버가 기억한 마감으로 Redis 없이 답한다. 받은 사람인지 확인하지 않으므로 쿠폰함 안내를 붙인다. */
    static final String CLOSED_SOLD_OUT = "쿠폰 발급 한도가 소진되었습니다. 이미 받으셨다면 쿠폰함에서 확인해 주세요.";
    static final String CLOSED_ENDED = "종료된 쿠폰 행사입니다. 이미 받으셨다면 쿠폰함에서 확인해 주세요.";
    static final String INACTIVE = "발급이 비활성화된 캠페인입니다.";
    static final String IN_PROGRESS = "같은 쿠폰 발급 요청을 확인하고 있습니다. 잠시 후 다시 확인해 주세요.";
    static final String NOT_PREPARED = "쿠폰 발급을 준비하고 있습니다. 잠시 후 다시 시도해 주세요.";
    static final String CANCELLED = "쿠폰 발급이 취소되었습니다. 발급 상태에서 이유를 확인해 주세요.";
    static final String GATE_UNAVAILABLE = "쿠폰 발급 상태를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";
    static final String LIMIT_NO_SEAT = "쿠폰 발급 요청이 많아 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.";
    static final String LIMIT_UNAVAILABLE = "쿠폰 발급 순서를 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.";

    private CouponRejections() {}
}
