package com.loopers.domain.coupon.vo;

import java.time.ZonedDateTime;

/** 202 를 응답하기 전에 잃지 않게 남기는 접수 사실 (8주차 D-132) */
public record IssueRequested(long campaignId, long userId, long sequence, ZonedDateTime requestedAt) {

    /** 같은 사람의 재전송이 같은 partition 으로 가고, consumer 가 이 값으로 멱등 적재한다 (8주차 D-136) */
    public String key() {
        return campaignId + ":" + userId;
    }
}
