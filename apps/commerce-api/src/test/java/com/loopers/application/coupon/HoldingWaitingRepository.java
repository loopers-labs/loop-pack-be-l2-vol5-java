package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.CouponWaitingSnapshot;
import com.loopers.domain.coupon.CouponWaitingState;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 대기실 저장소 test decorator. 실제 STARTED 직후(쓰기 TX 전)나 finish 위임 직전(DB 종료 확인 뒤·응답 전)에 지정한 수만큼 실행을
 * 유한 deadline 으로 붙잡고, start·finish 호출을 기록한다.
 * {@link #alwaysStarted()} 는 Redis 없이 start 를 STARTED 로만 답하는 대역이다. 같은 사용자 UNIQUE 후속 분기처럼 실제 Lua 가
 * 만들 수 없는 DB 경쟁을 보기 위한 것이며 실제 자리 상한의 근거로 쓰지 않는다.
 *
 * <p>서버 처리 종료 관찰: STARTED 가 아닌 start 결과는 DB 로 가지 않아 끝난 것이다. STARTED 는 그 waitingId 의 finish 위임이
 * 정상 반환돼야 끝난 것으로 본다. 진행 중·예외로 끝난 start(늦게 적용됐을 수 있음)와 finish 가 관찰되지 않은 STARTED 는 미확인이다.
 */
public final class HoldingWaitingRepository implements CouponAdmissionRepository {

    public record Finish(Long userId, String waitingId, CouponWaitingState outcome) {
    }

    private static final String IN_FLIGHT = "IN_FLIGHT";
    private static final String FAILED = "FAILED";

    private final CouponAdmissionRepository delegate;
    private final List<String> starts = Collections.synchronizedList(new ArrayList<>());
    private final List<String[]> startCalls = Collections.synchronizedList(new ArrayList<>());
    private final List<Finish> finishes = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> finished = ConcurrentHashMap.newKeySet();
    private volatile Hold hold;
    private volatile Hold finishHold;
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
        String[] call = {waitingId, IN_FLIGHT};
        startCalls.add(call);
        Start result;
        try {
            result = delegate == null ? new Start(StartOutcome.STARTED, null) : delegate.start(campaignId, userId, waitingId);
        } catch (RuntimeException e) {
            call[1] = FAILED;
            throw e;
        }
        call[1] = result.outcome().name();
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
        Hold current = finishHold;
        if (current != null) {
            current.maybeHold(waitingId); // DB 종료 확인 뒤·응답 전 단계다.
        }
        FinishOutcome result = delegate == null ? FinishOutcome.APPLIED : delegate.finish(campaignId, userId, waitingId, outcome);
        finished.add(waitingId);
        return result;
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

    public List<String> heldWaitingIds() {
        return List.copyOf(hold.held);
    }

    public void letThrough(int count) {
        hold.permits.release(count);
    }

    /** 이후 finish 중 처음 limit 개를 위임 전에 붙잡는다. */
    public void holdNextFinishes(int limit, Duration deadline) {
        finishHold = new Hold(limit, deadline);
    }

    public boolean awaitFinishHeld(Duration deadline) throws InterruptedException {
        return finishHold.reached.await(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    public List<String> finishHeldWaitingIds() {
        return List.copyOf(finishHold.held);
    }

    public void letFinishesThrough(int count) {
        finishHold.permits.release(count);
    }

    /** 붙잡기를 끝내고 남은 실행을 모두 놓는다. 실패 경로에서도 호출한다. */
    public void stopHolding() {
        Hold current = hold;
        hold = null;
        if (current != null) {
            current.permits.release(Integer.MAX_VALUE / 2);
        }
        Hold currentFinish = finishHold;
        finishHold = null;
        if (currentFinish != null) {
            currentFinish.permits.release(Integer.MAX_VALUE / 2);
        }
    }

    /** 앱 bean 으로 여러 사례가 공유할 때 사례 시작마다 기록을 비운다. 앞 사례의 종료 확인 뒤 호출한다. */
    public void reset() {
        stopHolding();
        starts.clear();
        startCalls.clear();
        finishes.clear();
        finished.clear();
        beforeCall = stage -> { };
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

    /** finish 위임이 정상 반환된 waitingId. */
    public Set<String> finishedWaitingIds() {
        return Set.copyOf(finished);
    }

    /** start 를 보낸 waitingId 전체. */
    public Set<String> startedWaitingIds() {
        Set<String> ids = new LinkedHashSet<>();
        synchronized (startCalls) {
            startCalls.forEach(call -> ids.add(call[0]));
        }
        return ids;
    }

    /** 서버 실행이 Redis·DB 작업을 끝냈다고 볼 근거가 없는 waitingId. */
    public Set<String> unsettledWaitingIds() {
        Set<String> unsettled = new HashSet<>();
        synchronized (startCalls) {
            for (String[] call : startCalls) {
                String state = call[1];
                boolean startedNotFinished = StartOutcome.STARTED.name().equals(state) && !finished.contains(call[0]);
                if (IN_FLIGHT.equals(state) || FAILED.equals(state) || startedNotFinished) {
                    unsettled.add(call[0]);
                }
            }
        }
        return unsettled;
    }

    /** finish 정상 반환이 count 번 관찰될 때까지 유한하게 기다린다. finish 는 Facade 발급의 마지막 단계다. */
    public boolean awaitFinished(int count, Duration deadline) throws InterruptedException {
        long until = System.nanoTime() + deadline.toNanos();
        while (finished.size() < count) {
            if (System.nanoTime() > until) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
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
        private final List<String> held = Collections.synchronizedList(new ArrayList<>());
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
            held.add(waitingId);
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
