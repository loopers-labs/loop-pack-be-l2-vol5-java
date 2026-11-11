package com.loopers.support.coupon;

import com.loopers.domains.coupon.app.port.CouponAdmission;
import com.loopers.domains.coupon.app.port.CouponIssueLookup;
import com.loopers.domains.coupon.app.port.CouponRepository;
import com.loopers.domains.coupon.domain.IssuedCoupon;
import com.loopers.domains.coupon.out.CouponPersistenceAdapter;
import com.loopers.domains.coupon.out.RedisCouponAdmission;
import com.loopers.support.pg.MutablePaymentClock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    @Bean
    @Primary
    RecordingCouponAdmission recordingCouponAdmission(RedisCouponAdmission delegate) {
        return new RecordingCouponAdmission(delegate);
    }

    // 실제 Redis 수용 어댑터에 위임하면서 호출 시점의 트랜잭션 여부를 기록하고, 획득 직후 붙잡기·예외 주입을 더한다.
    public static class RecordingCouponAdmission implements CouponAdmission {
        private final CouponAdmission delegate;
        private final List<Boolean> acquireInTransaction = new CopyOnWriteArrayList<Boolean>();
        private final List<Boolean> releaseInTransaction = new CopyOnWriteArrayList<Boolean>();
        private final AtomicReference<RuntimeException> acquireFailure = new AtomicReference<RuntimeException>();
        private final AtomicReference<RuntimeException> releaseFailure = new AtomicReference<RuntimeException>();
        private final AtomicReference<Runnable> afterAcquired = new AtomicReference<Runnable>(() -> { });

        RecordingCouponAdmission(CouponAdmission delegate) {
            this.delegate = delegate;
        }

        public void failNextAcquireWith(RuntimeException failure) {
            acquireFailure.set(failure);
        }

        public void failNextReleaseWith(RuntimeException failure) {
            releaseFailure.set(failure);
        }

        // 자리를 실제로 얻은 직후, 발급 트랜잭션을 시작하기 전에 실행한다(붙잡기용).
        public void onAcquired(Runnable action) {
            afterAcquired.set(action);
        }

        public List<Boolean> acquireInTransaction() {
            return List.copyOf(acquireInTransaction);
        }

        public List<Boolean> releaseInTransaction() {
            return List.copyOf(releaseInTransaction);
        }

        public void disarm() {
            acquireInTransaction.clear();
            releaseInTransaction.clear();
            acquireFailure.set(null);
            releaseFailure.set(null);
            afterAcquired.set(() -> { });
        }

        @Override
        public boolean acquire(String token, int capacity) {
            acquireInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            RuntimeException failure = acquireFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            boolean acquired = delegate.acquire(token, capacity);
            if (acquired) {
                afterAcquired.get().run();
            }
            return acquired;
        }

        @Override
        public void release(String token) {
            releaseInTransaction.add(TransactionSynchronizationManager.isActualTransactionActive());
            RuntimeException failure = releaseFailure.getAndSet(null);
            if (failure != null) {
                throw failure;
            }
            delegate.release(token);
        }
    }

    // 무장하면 다음 insert 한 번이 실제 어댑터를 부르지 않고 지정한 예외를 던진다. 앞선 조건부 UPDATE는 같은 트랜잭션에서 이미 DB로 나간 상태다.
    public static class FailingCouponRepository implements CouponRepository {
        private final CouponRepository delegate;
        private final AtomicReference<RuntimeException> insertFailure = new AtomicReference<RuntimeException>();
        private final AtomicInteger increaseIssuedCalls = new AtomicInteger();

        FailingCouponRepository(CouponRepository delegate) {
            this.delegate = delegate;
        }

        public void failNextInsertWith(RuntimeException failure) {
            insertFailure.set(failure);
        }

        public void disarm() {
            insertFailure.set(null);
            increaseIssuedCalls.set(0);
        }

        // 발급 트랜잭션(④)에 들어온 횟수.
        public int increaseIssuedCalls() {
            return increaseIssuedCalls.get();
        }

        @Override
        public Optional<CouponIssueLookup> findForIssue(long campaignId, long userId) {
            return delegate.findForIssue(campaignId, userId);
        }

        @Override
        public int increaseIssued(long campaignId) {
            increaseIssuedCalls.incrementAndGet();
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
