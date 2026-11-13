package com.loopers.domain.coupon.gateway;

import com.loopers.domain.coupon.vo.IssueEntry;
import com.loopers.domain.coupon.vo.PendingConfirm;

import java.time.Duration;
import java.util.List;

/** 캠페인별 발급 대기열(Redis Stream · consumer group)과 상태 사본. worker 인스턴스들이 같은 group 으로 나눠 읽는다 (8주차 D-118, D-119) */
public interface CouponIssueStream {

    /** 시각이 된 지연 항목을 대기열로 되돌린다 (D-129, D-130) */
    int moveDue(long campaignId, long nowMillis, int limit);

    /** 오래 처리 중인(죽은 worker 의) 항목을 이 consumer 로 가져온다 (D-119) */
    List<IssueEntry> claim(long campaignId, String consumer, Duration minIdle, int count);

    /** 접수 시각이 기준보다 이른데 아직 PENDING_CONFIRM 인 접수 (D-134) */
    List<PendingConfirm> stalePending(long campaignId, long acceptedBeforeMillis, int limit);

    /** PENDING_CONFIRM 을 WAITING(대기열) · RESERVE(예비 목록)로 확정한다. 이미 확정됐으면 그대로 둔다 (D-133) */
    void confirm(long campaignId, long userId);

    /** 새 항목을 이 consumer 몫으로 받는다. 대기열 · 표식이 없으면 비어 있다 */
    List<IssueEntry> read(long campaignId, String consumer, int count);

    /**
     * DB 발급 commit 뒤 부른다. 사용자 기록을 ISSUED 로 바꾸고 XACK 한다(같은 스크립트).
     * 이미 ISSUED · FAILED 면 기록은 그대로 두고 XACK 만 한다. 반복해도 결과가 같다 (D-125, D-126)
     */
    void completeIssued(IssueEntry entry, long couponId);

    /** 항목을 놓고 다음 시도 시각까지 지연 ZSET 에 둔다 (D-128, D-129) */
    void delay(IssueEntry entry, long nextAtMillis);

    /**
     * 최종 실패: 사용자 기록 FAILED 와 XACK. promote 면 가장 앞의 예비가 이 슬롯을 물려받는다(캠페인이 열려 있고 품절 전일 때만).
     * 이미 FAILED · ISSUED 면 승격하지 않는다 (D-120, D-123, D-124)
     *
     * @return 승격한 사용자 id, 없으면 null
     */
    Long fail(IssueEntry entry, boolean promote);

    /** 발급 수가 한도에 닿으면 세운다. 예비는 SOLD_OUT 으로 읽히고 승격 · 새 접수가 멈춘다 (D-125, D-142) */
    void markSoldOut(long campaignId);
}
