package com.loopers.interfaces.api.coupon;

import com.loopers.application.coupon.HoldingAdmissionRepository;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * HTTP 테스트가 token·DB 를 정리해도 되는지 판단한다. 클라이언트 worker 종료와 서버 발급/TX 종료는 다른 근거다.
 *
 * <p>서버 종료 근거로 인정하는 것:
 * <ul>
 *   <li>완전한 응답 수신(동기 Controller 는 Facade 처리 뒤 응답한다). 단 500 은 종료 불명일 수 있어 근거로 쓰지 않는다.</li>
 *   <li>수용 decorator 관찰: FULL 은 쓰기 TX 로 가지 않는다. ACQUIRED 는 반환 호출이 관찰돼야 끝난 것으로 본다.</li>
 * </ul>
 * 응답을 받지 못한 요청(연결 실패 등), 500, 진행 중·실패한 획득, 반환이 관찰되지 않은 ACQUIRED 는 미확인으로 남는다.
 * 미확인은 이후 다른 요청의 성공으로 지워지지 않는다. client future 의 실패·timeout·cancel, shutdownNow, 빈 Set 은 근거가 아니다.
 * 테스트가 직접 넣은 token 은 서버 실행이 아니므로 판단에서 뺀다.
 *
 * <p>HTTP 테스트에서는 컨텍스트 bean 으로 둔다. 한 번 정리를 보류하면 그 사유를 컨텍스트가 닫힐 때까지 유지해, 같은 컨텍스트의
 * 이후 사례가 시작·정리를 하지 못하게 한다. 응답을 잃은 요청은 아직 token 이 없을 수 있으므로(사전 DB 조회 중) 빈 Set·다른 요청의
 * 성공으로 이 상태를 풀지 않는다. 새 컨텍스트는 새 bean 이라 이전 컨텍스트의 보류와 섞이지 않는다.
 */
final class ServerSettlement {

    private final HoldingAdmissionRepository holding;
    private final Set<String> testTokens = ConcurrentHashMap.newKeySet();
    private final List<String> unconfirmedRequests = Collections.synchronizedList(new ArrayList<>());
    private final List<String> withheld = Collections.synchronizedList(new ArrayList<>());
    private final Map<String, String> pendingRaw = new ConcurrentHashMap<>();

    ServerSettlement(HoldingAdmissionRepository holding) {
        this.holding = holding;
    }

    void markTestToken(String token) {
        testTokens.add(token);
    }

    /**
     * 응답을 읽지 않는 raw 요청을 전송 전에 미확인으로 등록한다. 전송 실패·timeout·RST 는 해소 근거가 아니다.
     * {@link #settleRaw} 로 이 요청의 token 반환이 관찰됐을 때만 해소된다. 해소되지 않으면 정리 판단에서 사유로 남는다.
     */
    String registerRaw(String label) {
        String key = label + " #" + (pendingRaw.size() + 1);
        pendingRaw.put(key, "raw 요청 전송 전 등록, 서버 종료 근거 없음");
        return key;
    }

    /**
     * raw 요청의 서버 종료 근거를 연결한다. 그 요청이 획득한 token 의 반환(Facade 의 마지막 단계)이 decorator 에 관찰돼야 해소한다.
     * 근거가 없으면 미확인을 유지하고 예외를 던진다.
     */
    void settleRaw(String key, String token) {
        if (!pendingRaw.containsKey(key)) {
            throw new IllegalStateException("등록되지 않은 raw 요청: " + key);
        }
        if (token == null || !holding.releasedTokens().contains(token)) {
            throw new IllegalStateException("raw 요청의 서버 종료 근거(token 반환 관찰)가 없다: " + key + " token=" + token);
        }
        pendingRaw.remove(key);
    }

    /** 응답을 받지 못하면 미확인을 남기고 원래 예외를 그대로 던진다. */
    <T> ResponseEntity<T> observe(String label, Supplier<ResponseEntity<T>> call) {
        ResponseEntity<T> response;
        try {
            response = call.get();
        } catch (RuntimeException | Error e) {
            unconfirmedRequests.add(label + ": 응답 없음(" + e.getClass().getSimpleName() + ")");
            throw e;
        }
        if (response.getStatusCode().value() == 500) {
            unconfirmedRequests.add(label + ": 500(종료 불명 가능)");
        }
        return response;
    }

    /**
     * 반환이 늦게 관찰될 수 있으므로 deadline 안에서 서버 측 미확인 token 이 사라지길 기다린다.
     * 남은 사유를 돌려주며, 빈 목록일 때만 정리해도 된다.
     */
    List<String> awaitSettled(Duration deadline) throws InterruptedException {
        long until = System.nanoTime() + deadline.toNanos();
        Set<String> unsettled = serverUnsettled();
        while (!unsettled.isEmpty() && System.nanoTime() < until) {
            Thread.sleep(20);
            unsettled = serverUnsettled();
        }
        List<String> reasons = new ArrayList<>();
        synchronized (unconfirmedRequests) {
            reasons.addAll(unconfirmedRequests);
        }
        pendingRaw.forEach((key, state) -> reasons.add(key + ": " + state));
        unsettled.forEach(token -> reasons.add("서버 실행 종료 미관찰 token " + token));
        return reasons;
    }

    /** 사례 시작 전 호출한다. 보류된 컨텍스트면 fixture 준비·기록 초기화·수용 열기 전에 실패한다. */
    void requireNotWithheld() {
        synchronized (withheld) {
            if (!withheld.isEmpty()) {
                throw new IllegalStateException("이 컨텍스트는 앞선 사례가 서버 종료 미확인으로 정리를 보류했다. " + withheld);
            }
        }
    }

    /**
     * 사례 정리 직전 호출한다. 이미 보류됐거나, 클라이언트 worker 종료·서버 종료 근거가 없거나, 확인 대기가 interrupt 되면
     * 보류 사유를 남기고 예외로 끝난다. 정상 반환일 때만 호출자가 token·DB 를 정리한다.
     */
    void confirmCleanupOrWithhold(boolean workersTerminated, Duration deadline) {
        requireNotWithheld();
        List<String> reasons = new ArrayList<>();
        if (!workersTerminated) {
            reasons.add("클라이언트 worker 종료 미확인");
        }
        try {
            reasons.addAll(awaitSettled(deadline));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            reasons.add("서버 종료 확인 대기 중 interrupt");
        }
        if (!reasons.isEmpty()) {
            withheld.addAll(reasons);
            throw new IllegalStateException("서버 발급/TX 종료 근거가 없어 token·DB 정리를 보류한다. " + reasons
                + " [보낸 token = " + holding.sentTokens() + "]");
        }
    }

    /** 보류되지 않은 컨텍스트에서 사례 시작 때만 호출한다. 앞 사례는 모두 종료가 확인돼 기록을 비워도 된다. */
    void reset() {
        requireNotWithheld();
        testTokens.clear();
        unconfirmedRequests.clear();
        pendingRaw.clear();
    }

    private Set<String> serverUnsettled() {
        Set<String> unsettled = new HashSet<>(holding.unsettledTokens());
        unsettled.removeAll(testTokens);
        return unsettled;
    }
}
