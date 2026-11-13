package com.loopers.domain.promotion;

/** 프로모션 진행 상태. 참여 가능 여부는 이 상태만으로 판단한다 (기간은 메타데이터). */
public enum PromotionStatus {
    /** 만들어졌지만 아직 열지 않았다. */
    READY,
    /** 참여할 수 있다. */
    ACTIVE,
    /** 일시 중지. 다시 열 수 있다. */
    PAUSED,
    /** 끝났다. 다른 상태로 가지 않는다. */
    ENDED
}
