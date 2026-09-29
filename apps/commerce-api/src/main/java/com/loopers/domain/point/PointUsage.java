package com.loopers.domain.point;

import com.loopers.domain.common.Money;

/**
 * 결제 한 번에서 어느 그룹에서 얼마를 썼는지 (PNT-05). 저장할 때 사용 이력 한 줄이 된다.
 */
public record PointUsage(PointGroup group, Money amount) {}
