package com.loopers.domain.coupon.gateway;

import com.loopers.domain.coupon.vo.IssueAcceptance;
import com.loopers.domain.coupon.vo.IssueSnapshot;

import java.util.Optional;

/**
 * 선착순 판정 · 발급 대기열 · 상태 사본을 맡는 저장소 (8주차 D-115, D-119, D-125).
 * 한도 · 예비 범위 · 상태는 캠페인을 열 때 저장소에 쓴 메타에서 읽어, 판정 전에 DB 를 거치지 않는다.
 * 표식 키가 없으면 상태를 잃은 것으로 보고 COUPON_ISSUE_UNAVAILABLE 을 던진다 (8주차 D-139).
 * 저장소 접근 실패 · timeout 은 그대로 전파한다. 결과를 모르므로 호출자는 끝났다고 추측하지 않는다
 */
public interface CouponIssueQueue {

    /** DB 를 OPEN 으로 바꾼 뒤 부른다. 메타를 쓰고 표식을 마지막에 쓴다. 이미 열려 있으면 그대로 둔다 (D-166) */
    void open(long campaignId, int totalQuota, int reserveLimit, String epoch);

    /** DB 를 CLOSED 로 바꾼 뒤 부른다. 새 접수를 막고 품절 플래그를 세운다 (D-124, D-166) */
    void close(long campaignId);

    /** 예비 범위(한도 + 예비 인원)를 넘으면 기록 없이 즉시 품절 */
    IssueAcceptance accept(long campaignId, long userId, long nowMillis);

    /** PENDING_CONFIRM 을 WAITING(대기열) · RESERVE(예비 목록)로 확정한다. 이미 확정됐으면 그대로 돌려준다 */
    Optional<IssueSnapshot> confirm(long campaignId, long userId);

    Optional<IssueSnapshot> read(long campaignId, long userId);
}
