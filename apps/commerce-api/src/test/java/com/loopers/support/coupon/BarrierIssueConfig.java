package com.loopers.support.coupon;

import com.loopers.domain.coupon.CouponIssueTransactions;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** CouponIssueService를 barrier를 건 서비스로 바꾼다. 쓰는 테스트가 @Import한다. */
@TestConfiguration
public class BarrierIssueConfig {

    @Bean
    @Primary
    public BarrierIssueService barrierIssueService(CouponIssueTransactions transactions) {
        return new BarrierIssueService(transactions);
    }
}
