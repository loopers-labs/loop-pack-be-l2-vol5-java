package com.loopers.domain.point;

import com.loopers.domain.common.Money;

import java.time.Instant;

public interface PointUsage {

    Money use(Long userId, Money amount, Instant now);
}
