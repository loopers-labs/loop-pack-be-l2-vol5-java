package com.loopers.application.coupon;

import java.time.Instant;

public record CouponIssueGatePreparation(Long campaignId, int remaining, int issuedUsers, Instant endAt, Instant expireAt) {}
