package com.loopers.domains.coupon.app;

import java.time.Duration;
import java.time.Instant;

// 재개 확인 모드 보고(D140). 2·3·4가 모두 ✓일 때만 safeToOpen이다.
public record AdmissionCheckReport(boolean open, String closedReason, Instant closedAt, Duration sinceClosed, long epoch,
    Criterion time, Criterion database, Criterion reconcile, Long holders, boolean safeToOpen) {

    public record Criterion(boolean ok, String detail) { }
}
