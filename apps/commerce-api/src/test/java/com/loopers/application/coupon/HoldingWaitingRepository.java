package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 대기실 저장소 test decorator. 실제 STARTED 직후·쓰기 TX 전에 지정한 수만큼 실행을 유한 deadline 으로 붙잡고, start·finish 호출을 기록한다.
 * {@link #alwaysStarted()} 는 Redis 없이 start 를 STARTED 로만 답하는 대역이다. 같은 사용자 UNIQUE 후속 분기처럼 실제 Lua 가
 * 만들 수 없는 DB 경쟁을 보기 위한 것이며 실제 자리 상한의 근거로 쓰지 않는다. 기본 token 경로는 호출되면 실패한다.
 */
public final class HoldingWaitingRepository implements CouponAdmissionRepository {

    public record Finish(Long userId, String waitingId, CouponWaitingState outcome) {
    }

    private final CouponAdmissionRepository delegate;
    private final List<String> starts = Collections.synchronizedList(new ArrayList<>());
    private final List<Finish> finishes = Collections.synchronizedList(new ArrayList<>());
    private volatile Hold hold;
    private volatile Consumer<String> beforeCall = stage -> { };

    private HoldingWaitingRepository(CouponAdmissionRepository delegate) {
        this.delegate = delegate;
    }

    public static HoldingWaitingRepository real(CouponAdmissionRepository delegate) {
        return new HoldingWaitingRepository(delegate);
    }

    public static HoldingWaitingRepository alwaysStarted() {
        return new HoldingWaitingRepository(null);
    }

    @Override
    public Acquisition tryAcquire(String token, int capacity) {
        throw new UnsupportedOperationException("대기실 발급은 기본 token 자리를 쓰지 않는다.");
    }

    @Override
    public long release(String token) {
        throw new UnsupportedOperationException("대기실 발급은 기본 token 자리를 쓰지 않는다.");
    }

    @Override
    public Registration register(Long campaignId, Long userId, String newWaitingId) {
        return real().register(campaignId, userId, newWaitingId);
    }

    @Override
    public Optional<CouponWaitingSnapshot> find(Long campaignId, Long userId) {
        return real().find(campaignId, userId);
    }

    @Override
    public AdmissionProgress processAdmissions(Long campaignId, int capacity, long admissionDurationMillis, boolean soldOut, boolean canAdmit) {
        return real().processAdmissions(campaignId, capacity, admissionDurationMillis, soldOut, canAdmit);
    }

    @Override
    public Start start(Long campaignId, Long userId, String waitingId) {
        beforeCall.accept("start");
        Start result = delegate == null ? new Start(StartOutcome.STARTED, null) : delegate.start(campaignId, userId, waitingId);
        starts.add(waitingId + "=" + result.outcome());
        Hold current = hold;
        if (result.outcome() == StartOutcome.STARTED && current != null) {
            current.maybeHold(waitingId);
        }
        return result;
    }

    @Override
    public FinishOutcome finish(Long campaignId, Long userId, String waitingId, CouponWaitingState outcome) {
        beforeCall.accept("finish");
        finishes.add(new Finish(userId, waitingId, outcome));
        return delegate == null ? FinishOutcome.APPLIED : delegate.finish(campaignId, userId, waitingId, outcome);
    }

    /** start·finish 위임 직전에 호출할 관찰. TX 경계 확인용이다. */
    public void beforeStartOrFinish(Consumer<String> observer) {
        this.beforeCall = observer;
    }

    /** 이후 STARTED 된 실행 중 처음 limit 개를 붙잡는다. */
    public void holdNextStarted(int limit, Duration deadline) {
        hold = new Hold(limit, deadline);
    }

    public boolean awaitHeld(Duration deadline) throws InterruptedException {
        return hold.reached.await(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    public void letThrough(int count) {
        hold.permits.release(count);
    }

    /** 붙잡기를 끝내고 남은 실행을 모두 놓는다. 실패 경로에서도 호출한다. */
    public void stopHolding() {
        Hold current = hold;
        hold = null;
        if (current != null) {
            current.permits.release(Integer.MAX_VALUE / 2);
        }
    }

    public List<String> starts() {
        synchronized (starts) {
            return List.copyOf(starts);
        }
    }

    public List<Finish> finishes() {
        synchronized (finishes) {
            return List.copyOf(finishes);
        }
    }

    private CouponAdmissionRepository real() {
        if (delegate == null) {
            throw new UnsupportedOperationException("alwaysStarted 대역은 start·finish 만 지원한다.");
        }
        return delegate;
    }

    private static final class Hold {
        private final int limit;
        private final Duration deadline;
        private final AtomicInteger arrivals = new AtomicInteger();
        private final CountDownLatch reached;
        private final Semaphore permits = new Semaphore(0);

        private Hold(int limit, Duration deadline) {
            this.limit = limit;
            this.deadline = deadline;
            this.reached = new CountDownLatch(limit);
        }

        private void maybeHold(String waitingId) {
            if (arrivals.getAndIncrement() >= limit) {
                return;
            }
            reached.countDown();
            try {
                if (!permits.tryAcquire(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("barrier deadline 초과 [waitingId = " + waitingId + "]");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("barrier 대기 중 interrupt [waitingId = " + waitingId + "]", e);
            }
        }
    }
}
