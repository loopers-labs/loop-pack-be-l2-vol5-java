package com.loopers.domain.point;

import java.time.ZonedDateTime;
import java.util.List;

public interface PointGroupRepository {

    PointGroup save(PointGroup group);

    /**
     * 남은 금액이 있는 사용자의 그룹을 만료 시각이 이른 순(같으면 먼저 충전한 순)으로 돌려준다.
     * 만료 여부는 여기서 거르지 않는다 — "지금 쓸 수 있나"는 그룹이 답한다 (PNT-07).
     */
    List<PointGroup> findRemainingByUserId(Long userId);

    /**
     * 만료 처리 대상: 만료 시각이 기준 시각 이하(P-20 정각부터 만료)이고 남은 금액이 있는 그룹을 id 순으로 limit개까지 돌려준다 (PNT-08, ADR-10).
     */
    List<PointGroup> findExpirable(ZonedDateTime now, int limit);
}
