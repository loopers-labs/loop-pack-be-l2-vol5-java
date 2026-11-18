package com.loopers.domain.coupon.enums;

/** 행사 자체의 상태. Redis 장애로 접수를 닫는 운영 상태와는 따로 둔다 (8주차 D-151) */
public enum CampaignStatus {
    READY,
    OPEN,
    CLOSED
}
