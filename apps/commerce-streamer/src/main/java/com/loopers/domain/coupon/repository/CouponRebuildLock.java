package com.loopers.domain.coupon.repository;

/** 여러 인스턴스 중 하나만 재구성하게 하는 잠금. Redis 를 잃은 상황이라 DB 에 둔다 (8주차 D-174) */
public interface CouponRebuildLock {

    /** 잠금을 잡으면 task 를 실행하고 true, 못 잡으면 실행하지 않고 false */
    boolean runExclusively(long campaignId, Runnable task);
}
