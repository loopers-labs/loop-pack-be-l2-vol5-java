package com.loopers.application.coupon.port;

import com.loopers.application.coupon.support.CouponExecution;
import java.util.List;

/** 실제 쓰기 DB에서 종료와 정합성을 확인한다. 확인 실패는 종료 증거가 아니다. */
public interface CouponExecutionEvidence {
    boolean ended(CouponExecution.DbWork work, long campaignId);
    boolean readyAfterRestart(List<CouponExecution.DbWork> previousSessions);
}
