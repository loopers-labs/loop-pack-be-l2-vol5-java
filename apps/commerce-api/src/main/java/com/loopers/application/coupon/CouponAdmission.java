package com.loopers.application.coupon;

import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 쿠폰 신규 발급의 동시 처리 자리 문(docs/week8/decisions.md INV-803~805, D-2~D-7, D-10, D-11, D-14, D-16).
 *
 * <ul>
 *   <li>열림/닫힘 표시는 이 서버의 메모리에 둔다. 켜질 때 기본값은 닫힘이다(D-7). Redis 오류를 한 번이라도 보면 그 자리에서 닫고,
 *       연결이 돌아와도 스스로 열지 않는다(D-6). 사람이 D-5 순서로 확인한 뒤 {@link #open()}을 부른다.</li>
 *   <li>자리 주인은 실행이다. 실행마다 {@code {서버ID}:{UUID}} token을 만들고, 반환은 자기 token만 지운다(D-4).
 *       서버ID는 빈이 만들어질 때 한 번 정한다 — 남은 token의 범위를 구분하는 데 쓴다(D-5).</li>
 *   <li>{@link #open()}, {@link #close()}, {@link #releaseLeftover(String)}는 HTTP 없이 빈 메서드로만 둔다(D-11).</li>
 * </ul>
 */
@Slf4j
@Component
public class CouponAdmission {

    private final AdmissionPermits permits;
    private final int capacity;
    private final String serverId = UUID.randomUUID().toString();
    private final AtomicBoolean open = new AtomicBoolean(false);

    public CouponAdmission(AdmissionPermits permits, @Value("${coupon.admission.capacity:3}") int capacity) {
        this.permits = permits;
        this.capacity = capacity;
    }

    /**
     * 자리를 잡고 이번 실행의 token을 돌려준다.
     *
     * @throws CoreException 닫혀 있거나 Redis 응답을 확인할 수 없으면 503(이때 닫는다), 자리가 없으면 429.
     */
    public String acquire() {
        if (!open.get()) {
            throw unavailable();
        }
        String token = serverId + ":" + UUID.randomUUID();
        boolean admitted;
        try {
            admitted = permits.tryAcquire(token, capacity);
        } catch (RuntimeException e) {
            // 불확실한 응답이다 — Lua가 실행돼 token이 들어갔는지 알 수 없다(D-14). 남아 있다면 D-5 순서로 지운다.
            close();
            log.error("쿠폰 수용: 자리 확인 실패로 수용을 닫는다. 이 token이 장부에 남았을 수 있다 token={}", token, e);
            throw unavailable();
        }
        if (!admitted) {
            throw new CoreException(ErrorType.TOO_MANY_REQUESTS, "지금은 쿠폰 발급 요청이 많아 처리할 수 없습니다.");
        }
        return token;
    }

    /**
     * 자기 token을 반환한다. 실패해도 예외를 던지지 않는다 — 업무 결과(쿠폰, 응답)는 그대로 두고 수용만 닫는다(D-10, INV-804).
     */
    public void release(String token) {
        try {
            permits.release(token);
        } catch (RuntimeException e) {
            close();
            log.error("쿠폰 수용: 자리 반환 실패로 수용을 닫는다. 이 token의 실행은 끝났다 — D-5 순서로 지운다 token={}", token, e);
        }
    }

    /** 수용을 연다. D-5 순서(닫기 → 확인 → 남은 token 정리)를 마친 뒤에만 부른다. */
    public void open() {
        open.set(true);
        log.warn("쿠폰 수용: 열림 serverId={}", serverId);
    }

    /** 수용을 닫는다. 메모리 표시만 바꾸므로 실패하지 않는다(D-7). */
    public void close() {
        if (open.getAndSet(false)) {
            log.warn("쿠폰 수용: 닫힘 serverId={}", serverId);
        }
    }

    public boolean isOpen() {
        return open.get();
    }

    /**
     * 확인을 마친 남은 token 하나를 지운다(D-5 3단계). 수용이 닫혀 있을 때만 받는다 — 열린 채로 지우면 확인하는 사이 새 실행이 섞인다.
     * Redis 오류는 그대로 던진다(관리자가 실패를 봐야 한다).
     */
    public void releaseLeftover(String token) {
        if (open.get()) {
            throw new IllegalStateException("수용이 열려 있다. close() 뒤 확인을 마치고 지운다(D-5).");
        }
        permits.release(token);
        log.warn("쿠폰 수용: 남은 token 정리 token={}", token);
    }

    /** 지금 장부에 있는 token. 운영 확인(D-5 2단계)용. Redis 오류는 그대로 던진다. */
    public Set<String> holders() {
        return permits.holders();
    }

    public String serverId() {
        return serverId;
    }

    public int capacity() {
        return capacity;
    }

    private static CoreException unavailable() {
        return new CoreException(ErrorType.SERVICE_UNAVAILABLE, "지금은 쿠폰 발급을 받을 수 없습니다.");
    }
}
