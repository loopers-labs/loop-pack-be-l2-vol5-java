package com.loopers.domain.coupon;

import java.time.Instant;

/** 작업 큐에 쌓인 발급 작업 하나. reservation은 접수 때 Redis에 남긴 예약 값이고, acceptedAt은 접수 시각이다. */
public record CouponIssueJob(String id, Long campaignId, Long userId, String reservation, Instant acceptedAt) {}
