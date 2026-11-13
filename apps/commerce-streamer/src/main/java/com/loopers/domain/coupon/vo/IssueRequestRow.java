package com.loopers.domain.coupon.vo;

/** 재구성에 쓰는 DB 접수 기록 한 행 (8주차 D-174) */
public record IssueRequestRow(long userId, long sequence, String status, Integer slotNo, Long couponId, boolean promoted) {}
