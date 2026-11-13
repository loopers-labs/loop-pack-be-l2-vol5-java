package com.loopers.domain.coupon;

import java.util.List;

/** 작업 큐(Worker 쪽). 등록은 판단과 같은 Lua에서 하므로 {@link CouponIssueGate#enqueue}가 한다. */
public interface CouponIssueJobs {
    /** 큐를 비우고 처리 준비를 한다. 발급 요청과 처리 중인 작업이 없을 때만 부른다. 큐에는 만료를 걸지 않는다. */
    void reset(Long campaignId);

    /**
     * 처리할 작업을 가져온다. 이 Worker가 가져갔지만 처리 완료를 표시하지 않은 작업을 먼저 다시 준다.
     * 그런 작업이 없을 때만 새 작업을 준다. 접수가 끝났고 남은 작업이 없는 큐는 이때 정리한다.
     */
    List<CouponIssueJob> poll(int max);

    /** 처리 완료를 표시한다. 표시 전에 Worker가 멈추면 다음 poll에서 다시 받는다. */
    void acknowledge(CouponIssueJob job);

    /**
     * 계속 실패하는 작업을 보류함에 옮기고 처리 완료로 표시해 뒤 작업을 막지 않게 한다.
     * 예약(받을 권리)은 지우지 않는다. 보류된 작업은 수동 확인 대상이며, 보류함이 비기 전에는 큐를 정리하지 않는다.
     */
    void hold(CouponIssueJob job, String reason);
}
