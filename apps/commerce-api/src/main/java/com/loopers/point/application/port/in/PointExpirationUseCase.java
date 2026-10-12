package com.loopers.point.application.port.in;

import java.time.ZonedDateTime;

/**
 * 포인트 만료 처리의 입구(입력 포트). 스케줄러 어댑터가 부른다 (PNT-08, ADR-10).
 */
public interface PointExpirationUseCase {

    /**
     * 기준 시각에 만료된 그룹을 모두 처리하고, 처리한 그룹 수를 돌려준다.
     */
    int expireAll(ZonedDateTime now);
}
