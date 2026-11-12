package com.loopers.application.coupon;

import com.loopers.domain.coupon.CouponAdmissionRepository;
import com.loopers.domain.coupon.TokenOnlyAdmissionRepository;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * test 소스의 수용 저장소 decorator. 실제 Redis 저장소에 위임하고, 실제 ACQUIRED 직후·쓰기 TX 전에 지정한 수만큼 실행을 붙잡는다.
 * 붙잡기는 유한한 deadline 을 가지며 넘으면 예외다(서비스는 이를 획득 불명으로 보고 닫는다).
 * 이 테스트가 Redis 에 보낸 token 을 모두 기록해 정리 대상을 자기 token 으로 한정하고, 반환 호출을 서버 측 완료 관찰로 쓴다.
 */
public class HoldingAdmissionRepository implements TokenOnlyAdmissionRepository {

    private static final String IN_FLIGHT = "IN_FLIGHT";
    private static final String FAILED = "FAILED";

    private final CouponAdmissionRepository delegate;
    private final Set<String> sentTokens = ConcurrentHashMap.newKeySet();
    private final List<String> releasedTokens = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> acquireOutcomes = new ConcurrentHashMap<>();
    private volatile Hold hold;
    private volatile Hold releaseHold;

    public HoldingAdmissionRepository(CouponAdmissionRepository delegate) {
        this.delegate = delegate;
    }

    @Override
    public Acquisition tryAcquire(String token, int capacity) {
        sentTokens.add(token);
        acquireOutcomes.put(token, IN_FLIGHT);
        Acquisition result;
        try {
            result = delegate.tryAcquire(token, capacity);
        } catch (RuntimeException e) {
            acquireOutcomes.put(token, FAILED);
            throw e;
        }
        acquireOutcomes.put(token, String.valueOf(result));
        Hold current = hold;
        if (result == Acquisition.ACQUIRED && current != null) {
            current.maybeHold(token);
        }
        return result;
    }

    @Override
    public long release(String token) {
        sentTokens.add(token);
        Hold currentRelease = releaseHold;
        if (currentRelease != null) {
            currentRelease.maybeHold(token); // 반환은 commit 확인 뒤·응답 전 단계다.
        }
        long removed = delegate.release(token);
        releasedTokens.add(token);
        return removed;
    }

    /** 이후 ACQUIRED 된 실행 중 처음 limit 개를 붙잡는다. 그 뒤의 실행은 그대로 통과한다. */
    public void holdNextAcquired(int limit, Duration deadline) {
        hold = new Hold(limit, deadline);
    }

    /** limit 개가 붙잡혔는지 deadline 안에 기다린다. */
    public boolean awaitHeld(Duration deadline) throws InterruptedException {
        return hold.reached.await(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    public List<String> heldTokens() {
        return List.copyOf(hold.held);
    }

    public void letThrough(int count) {
        hold.permits.release(count);
    }

    /** 이후 반환 중 처음 limit 개를 반환 명령 위임 전에 붙잡는다. 이 지점은 쓰기 TX 종료 확인 뒤·HTTP 응답 전이다. */
    public void holdNextReleases(int limit, Duration deadline) {
        releaseHold = new Hold(limit, deadline);
    }

    public boolean awaitReleaseHeld(Duration deadline) throws InterruptedException {
        return releaseHold.reached.await(deadline.toMillis(), TimeUnit.MILLISECONDS);
    }

    public List<String> releaseHeldTokens() {
        return List.copyOf(releaseHold.held);
    }

    public void letReleasesThrough(int count) {
        releaseHold.permits.release(count);
    }

    /** 붙잡기를 끝내고 남은 실행을 모두 놓는다. 실패 경로에서도 호출한다. */
    public void stopHolding() {
        Hold current = hold;
        hold = null;
        if (current != null) {
            current.permits.release(Integer.MAX_VALUE / 2);
        }
        Hold currentRelease = releaseHold;
        releaseHold = null;
        if (currentRelease != null) {
            currentRelease.permits.release(Integer.MAX_VALUE / 2);
        }
    }

    /** 앱 bean 으로 여러 사례가 공유할 때 사례 시작마다 기록을 비운다. 이전 사례의 token 정리를 마친 뒤 호출한다. */
    public void reset() {
        stopHolding();
        sentTokens.clear();
        releasedTokens.clear();
        acquireOutcomes.clear();
    }

    /**
     * 서버 실행이 Redis·DB 작업을 끝냈다고 볼 근거가 없는 token. 획득 명령이 진행 중이거나 실패(늦게 추가될 수 있음)했거나,
     * ACQUIRED 뒤 반환이 관찰되지 않은 경우다. FULL 은 쓰기 TX 로 가지 않으므로 포함하지 않는다.
     * 반환 명령이 예외로 끝난 token 도 반환 관찰이 없어 여기에 남는다.
     */
    public Set<String> unsettledTokens() {
        Set<String> released;
        synchronized (releasedTokens) {
            released = Set.copyOf(releasedTokens);
        }
        Set<String> unsettled = new HashSet<>();
        acquireOutcomes.forEach((token, outcome) -> {
            boolean acquiredNotReleased = Acquisition.ACQUIRED.name().equals(outcome) && !released.contains(token);
            if (IN_FLIGHT.equals(outcome) || FAILED.equals(outcome) || acquiredNotReleased) {
                unsettled.add(token);
            }
        });
        return unsettled;
    }

    public Set<String> sentTokens() {
        return Set.copyOf(sentTokens);
    }

    public List<String> releasedTokens() {
        synchronized (releasedTokens) {
            return List.copyOf(releasedTokens);
        }
    }

    /** 반환 호출이 count 번 관찰될 때까지 기다린다. 반환은 Facade 의 마지막 단계라 서버 측 처리 종료의 관찰로 쓴다. */
    public boolean awaitReleases(int count, Duration deadline) throws InterruptedException {
        long until = System.nanoTime() + deadline.toNanos();
        while (releasedTokens.size() < count) {
            if (System.nanoTime() > until) {
                return false;
            }
            Thread.sleep(10);
        }
        return true;
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

        private void maybeHold(String token) {
            if (arrivals.getAndIncrement() >= limit) {
                return;
            }
            held.add(token);
            reached.countDown();
            try {
                if (!permits.tryAcquire(deadline.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException("barrier deadline 초과 [token = " + token + "]");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("barrier 대기 중 interrupt [token = " + token + "]", e);
            }
        }
    }
}
