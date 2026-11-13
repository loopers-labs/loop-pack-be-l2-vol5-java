package com.loopers.domain.coupon.repository;

import com.loopers.domain.coupon.vo.CouponIssueRequested;

import java.util.List;

public interface CouponIssueRequestStore {

    /**
     * 접수 기록으로 묶어 적재한다. 이미 있는 (캠페인, 사용자)는 바꾸지 않는다(재전송 · worker 선반영과 겹쳐도 무해).
     *
     * @return 저장된 순번이 받은 것과 다르거나 저장되지 않은 접수. Redis 순번 배정이 어긋났다는 뜻이다
     */
    List<CouponIssueRequested> loadAll(List<CouponIssueRequested> requests);

    /** DB 접수 기록(약속의 원본)이 있는가 (D-134) */
    boolean exists(long campaignId, long userId);
}
