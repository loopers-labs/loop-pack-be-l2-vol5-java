package com.loopers.domain.coupon.vo;

/** 접수 Lua 1 의 결과 (8주차 D-117, D-121, D-133) */
public sealed interface IssueAcceptance {

    /** 새로 순번을 받아 PENDING_CONFIRM 으로 남았다. Kafka 확인 뒤 확정한다 */
    record Pending(long sequence) implements IssueAcceptance {}

    /** 이미 기록이 있어 새 순번을 주지 않았다 */
    record Existing(IssueSnapshot snapshot) implements IssueAcceptance {}

    /** 예비 범위 밖이거나 품절 플래그가 서 있다. 어디에도 기록하지 않는다 */
    record SoldOut() implements IssueAcceptance {}
}
