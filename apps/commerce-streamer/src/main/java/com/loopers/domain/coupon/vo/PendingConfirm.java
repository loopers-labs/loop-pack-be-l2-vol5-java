package com.loopers.domain.coupon.vo;

/** Kafka 확인을 받지 못해 아직 확정되지 않은 접수 (8주차 D-133, D-134) */
public record PendingConfirm(long campaignId, long userId, long sequence, long acceptedAtMillis) {}
