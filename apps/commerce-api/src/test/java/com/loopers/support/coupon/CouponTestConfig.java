package com.loopers.support.coupon;

import com.loopers.domains.coupon.app.port.CouponIssueLookup;
import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import com.loopers.domains.coupon.out.CouponPersistenceAdapter;
import com.loopers.support.pg.MutablePaymentClock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

// 쿠폰 발급 통합 테스트 전용 대역. 운영 코드에는 분기를 두지 않고 포트를 감싸 실패를 주입한다.
@TestConfiguration
public class CouponTestConfig {
    @Bean
    @Primary
    MutablePaymentClock couponTestClock() {
        return new MutablePaymentClock();
    }

    @Bean
    @Primary
    FailingCouponRepository failingCouponRepository(CouponPersistenceAdapter delegate) {
        return new FailingCouponRepository(delegate);
    }

    // 무장하면 다음 insert 한 번이 실제 어댑터를 부르지 않고 지정한 예외를 던진다. 앞선 조건부 UPDATE는 같은 트랜잭션에서 이미 DB로 나간 상태다.
    public static class FailingCouponRepository implements CouponRepository {
        private final CouponRepository delegate;
        private final AtomicReference<RuntimeException> insertFailure = new AtomicReference<RuntimeException>();

        FailingCouponRepository(CouponRepository delegate) {
            this.delegate = delegate;
        }

        public void failNextInsertWith(RuntimeException failure) {
            insertFailure.set(failure);
        }

        public void disarm() {
            insertFailure.set(null);
        }

        @Override
        public Optional<CouponIssueLookup> findForIssue(long campaignId, long userId) {
            return delegate.findForIssue(campaignId, userId);
        }

        @Override
        public int increaseIssued(long campaignId) {
            return delegate.increaseIssued(campaignId);
        }

        @Override
        public IssuedCoupon insert(long campaignId, long userId, Instant issuedAt) {
            RuntimeException failure = insertFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            return delegate.insert(campaignId, userId, issuedAt);
        }

        @Override
        public List<IssuedCoupon> findByUser(long userId) {
            return delegate.findByUser(userId);
        }
    }
}
