package com.loopers.domains.coupon.app;

import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import java.time.Clock;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CouponIssueService {
    public record IssueResult(IssuedCoupon coupon, boolean newlyIssued) { }

    private final CouponRepository couponRepository;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public CouponIssueService(CouponRepository couponRepository, TransactionTemplate transactionTemplate, Clock clock) {
        this.couponRepository = couponRepository;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public IssueResult issue(long campaignId, long userId) {
        return null;
    }

    public List<IssuedCoupon> listMine(long userId) {
        return List.of();
    }
}
