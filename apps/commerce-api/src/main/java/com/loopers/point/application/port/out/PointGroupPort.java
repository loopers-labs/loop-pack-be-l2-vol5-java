package com.loopers.point.application.port.out;

import com.loopers.point.domain.PointGroup;
import java.time.ZonedDateTime;
import java.util.List;

public interface PointGroupPort {

    PointGroup save(PointGroup group);

    /**
     * 남은 금액이 있는 사용자의 그룹을 만료 시각이 이른 순(같으면 먼저 충전한 순)으로 돌려준다.
     * 만료 여부는 여기서 거르지 않는다 — "지금 쓸 수 있나"는 그룹이 답한다 (PNT-07).
     */
    List<PointGroup> findRemainingByUserId(Long userId);

    /**
     * findRemainingByUserId와 같은 대상·순서를 쓰기 잠금으로 읽는다. 결제가 쓴다 (R-2, ADR-W3-02).
     */
    List<PointGroup> findRemainingByUserIdForUpdate(Long userId);


    /**
     * 만료 처리 대상: 만료 시각이 기준 시각 이하(P-20 정각부터 만료)이고 남은 금액이 있는 그룹을 limit개까지 쓰기 잠금으로 돌려준다 (PNT-08, ADR-10).
     * 순서는 결제와 같은 (만료 시각, id)라, 같은 사용자의 그룹을 결제와 반대 순서로 잠그지 않는다 (R-6, ADR-W3-03).
     */
    List<PointGroup> findExpirableForUpdate(ZonedDateTime now, int limit);
}
