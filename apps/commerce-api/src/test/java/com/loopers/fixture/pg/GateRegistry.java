package com.loopers.fixture.pg;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GATE plan 의 gate 상태. 대기는 이 객체의 잠금 밖에서 latch 로 하고, 상태 전이만 잠금 안에서 한다.
 * 등록 직후 해제하면 요청이 도착해도 기다리지 않는다.
 */
final class GateRegistry {

    private final Map<String, Gate> gates = new LinkedHashMap<>();

    synchronized void register(String gateId) {
        if (gates.containsKey(gateId)) {
            throw new IllegalArgumentException("이미 등록된 gate: " + gateId);
        }
        gates.put(gateId, new Gate());
    }

    synchronized void release(String gateId) {
        Gate gate = gates.get(gateId);
        if (gate == null) {
            throw new IllegalArgumentException("없는 gate: " + gateId);
        }
        if (gate.status != GateStatus.TIMED_OUT) {
            gate.status = GateStatus.RELEASED;
            gate.opened.countDown();
        }
    }

    /** close 용. 상태는 바꾸지 않고 대기만 깨운다. 깨어난 handler 는 종료 중임을 보고 응답 없이 닫는다. */
    synchronized void wakeAll() {
        gates.values().forEach(gate -> gate.opened.countDown());
    }

    /** @return 해제됐으면 true, 최대 대기를 넘겼으면 false */
    boolean await(String gateId, Duration timeout) throws InterruptedException {
        Gate gate;
        synchronized (this) {
            gate = gates.get(gateId);
            if (gate == null) {
                throw new IllegalStateException("plan 의 gate 가 없음: " + gateId);
            }
            if (gate.status == GateStatus.REGISTERED) {
                gate.status = GateStatus.WAITING;
            }
        }
        boolean opened = gate.opened.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
        synchronized (this) {
            if (!opened && gate.status != GateStatus.RELEASED) {
                gate.status = GateStatus.TIMED_OUT;
                return false;
            }
            return true;
        }
    }

    synchronized List<GateSnapshot> snapshot() {
        return gates.entrySet().stream()
            .map(entry -> new GateSnapshot(entry.getKey(), entry.getValue().status))
            .toList();
    }

    synchronized void clear() {
        gates.clear();
    }

    private static final class Gate {

        private final CountDownLatch opened = new CountDownLatch(1);
        private GateStatus status = GateStatus.REGISTERED;
    }
}
