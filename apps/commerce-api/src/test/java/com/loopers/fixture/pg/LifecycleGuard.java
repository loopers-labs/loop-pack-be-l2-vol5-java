package com.loopers.fixture.pg;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 종료 여부·처리 중 요청/콜백 작업 수·제어 호출 접수를 한 잠금으로 직렬화한다(5.7 lifecycle guard).
 * reset 의 busy 검사와 clear 사이에 새 handler·콜백 작업이 끼어들지 못한다.
 * delay·gate 대기는 이 잠금을 잡지 않고 {@link #awaitClosing} 의 latch 로 종료 신호를 받는다.
 */
final class LifecycleGuard {

    private final CountDownLatch closing = new CountDownLatch(1);
    private boolean closed;
    private int activeRequests;
    private int activeCallbackJobs;

    synchronized boolean tryEnterRequest() {
        if (closed) {
            return false;
        }
        activeRequests++;
        return true;
    }

    synchronized void exitRequest() {
        activeRequests--;
    }

    synchronized <T> T whileOpen(Supplier<T> action) {
        requireOpen();
        return action.get();
    }

    synchronized void whenQuiet(Runnable action) {
        requireOpen();
        if (activeRequests > 0 || activeCallbackJobs > 0) {
            throw new IllegalStateException(
                "처리 중인 PG 요청·콜백 작업이 있어 reset 불가: " + activeRequests + "/" + activeCallbackJobs);
        }
        action.run();
    }

    /** 접수(대기 포함)한 콜백 작업 수. 실제 전송 시도 수(callbackSent)와 다르다. */
    synchronized void acceptCallbackJobs(int count) {
        requireOpen();
        activeCallbackJobs += count;
    }

    synchronized void exitCallbackJob() {
        activeCallbackJobs--;
    }

    synchronized int activeCallbackJobs() {
        return activeCallbackJobs;
    }

    synchronized int activeRequests() {
        return activeRequests;
    }

    /** @return 이번 호출이 처음 종료 표시를 했으면 true */
    synchronized boolean markClosed() {
        if (closed) {
            return false;
        }
        closed = true;
        closing.countDown();
        return true;
    }

    boolean isClosing() {
        return closing.getCount() == 0;
    }

    /** @return 대기 중 종료 신호를 받았으면 true */
    boolean awaitClosing(long millis) throws InterruptedException {
        return closing.await(millis, TimeUnit.MILLISECONDS);
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("종료된 PG fixture");
        }
    }
}
