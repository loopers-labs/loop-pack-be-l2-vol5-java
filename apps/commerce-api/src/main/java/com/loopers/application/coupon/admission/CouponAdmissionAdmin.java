package com.loopers.application.coupon.admission;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * 관리자가 캠페인의 수용 상태를 준비·재구성·종료하는 포트. 상태를 바꾸지 못하면 {@link CouponAdmissionException} 을 던진다.
 */
public interface CouponAdmissionAdmin {

    /** 열지 않은 채로 준비한다(active=1, open=0). */
    void prepare(Long campaignId, int quota);

    /** 신규 판정을 멈춘다(open=0). */
    void suspend(Long campaignId);

    /** 당첨자 명단을 주어진 값으로 통째로 바꾼다. 값은 couponId 또는 TICKETED 다. */
    void replaceWinners(Long campaignId, Map<Long, String> winners);

    /** 주어진 token 만 자리에서 지운다. */
    void releaseTokens(Long campaignId, Collection<String> tokens);

    /** 한도·활성 여부를 맞추고 신규 판정을 연다(open=1). */
    void open(Long campaignId, int quota, boolean active);

    /** 캠페인을 종료한다(active=0). 이미 받은 사람의 결과 조회는 계속된다. */
    void deactivate(Long campaignId);

    /** 쿠폰이 아직 기록되지 않은 당첨자(PENDING·TICKETED). */
    List<Long> findUnissuedWinners(Long campaignId);
}
