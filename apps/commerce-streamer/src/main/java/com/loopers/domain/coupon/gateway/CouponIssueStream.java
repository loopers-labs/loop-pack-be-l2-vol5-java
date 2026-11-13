package com.loopers.domain.coupon.gateway;

import com.loopers.domain.coupon.vo.IssueEntry;

import java.util.List;

/** 캠페인별 발급 대기열(Redis Stream · consumer group). worker 인스턴스들이 같은 group 으로 나눠 읽는다 (8주차 D-118, D-119) */
public interface CouponIssueStream {

    /** 새 항목을 이 consumer 몫으로 받는다. 대기열 · 표식이 없으면 비어 있다 */
    List<IssueEntry> read(long campaignId, String consumer, int count);

    /**
     * DB 발급 commit 뒤 부른다. 사용자 기록을 ISSUED 로 바꾸고 XACK 한다(같은 스크립트).
     * 이미 ISSUED · FAILED 면 기록은 그대로 두고 XACK 만 한다. 반복해도 결과가 같다 (8주차 D-125, D-126)
     */
    void completeIssued(IssueEntry entry, long couponId);
}
