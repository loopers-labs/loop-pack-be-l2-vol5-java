package com.loopers.domain.point;

import java.util.List;

public interface PointGroupRepository {

    PointGroup save(PointGroup group);

    /**
     * 남은 금액이 있는 사용자의 그룹을 만료 시각이 이른 순(같으면 먼저 충전한 순)으로 돌려준다.
     * 만료 여부는 여기서 거르지 않는다 — "지금 쓸 수 있나"는 그룹이 답한다 (PNT-07).
     */
    List<PointGroup> findRemainingByUserId(Long userId);
}
