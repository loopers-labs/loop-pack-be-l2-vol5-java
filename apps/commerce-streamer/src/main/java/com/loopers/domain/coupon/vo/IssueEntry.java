package com.loopers.domain.coupon.vo;

/** 대기열에서 꺼낸 발급 항목 하나. slotNo 는 순번이거나 승격 때 물려받은 슬롯이다 (8주차 D-119, D-141) */
public record IssueEntry(long campaignId, String entryId, long userId, long sequence, int slotNo, String epoch) {}
