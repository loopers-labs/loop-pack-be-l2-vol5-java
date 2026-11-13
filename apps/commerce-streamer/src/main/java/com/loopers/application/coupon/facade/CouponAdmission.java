package com.loopers.application.coupon.facade;

import com.loopers.domain.coupon.gateway.AdmissionSlots;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 세마포어 실험의 수용 제어 (8주차 D-157 ~ D-162). 실제 발급 경로에는 쓰지 않는다 (D-114).
 * <ul>
 *   <li>수용 상태는 이 인스턴스의 메모리 플래그. 기본은 닫힘이고 명시적으로 연다 (D-160)</li>
 *   <li>획득 결과를 모르거나 반환이 실패하면 닫고, 건강 확인만으로 다시 열지 않는다</li>
 *   <li>token 은 "인스턴스 id:uuid". 재개 때 자기 접두어 중 실행 중이 아닌 것만 지운다 (D-162)</li>
 * </ul>
 */
@Slf4j
@Component
public class CouponAdmission {

    public sealed interface Result {
        record Acquired(long campaignId, String token) implements Result {}

        record Full() implements Result {}

        record Unavailable() implements Result {}
    }

    private final AdmissionSlots admissionSlots;
    private final String instanceId;
    private final int capacity;
    private final AtomicBoolean open = new AtomicBoolean(false);
    private final Set<String> running = ConcurrentHashMap.newKeySet();

    public CouponAdmission(
        AdmissionSlots admissionSlots,
        @Value("${coupon-issue.worker.instance-id}") String instanceId,
        @Value("${coupon-issue.admission.capacity}") int capacity
    ) {
        this.admissionSlots = admissionSlots;
        this.instanceId = instanceId;
        this.capacity = capacity;
    }

    public Result tryAcquire(long campaignId) {
        if (!open.get()) {
            return new Result.Unavailable();
        }
        String token = instanceId + ":" + UUID.randomUUID();
        boolean acquired;
        try {
            acquired = admissionSlots.acquire(campaignId, capacity, token);
        } catch (RuntimeException e) {
            // 실제로는 자리를 잡았을 수 있다. 끝났다고 추측하지 않고 닫는다
            close("획득 결과를 모름", campaignId, e);
            return new Result.Unavailable();
        }
        if (!acquired) {
            return new Result.Full();
        }
        running.add(token);
        return new Result.Acquired(campaignId, token);
    }

    /** DB TX 가 commit · rollback 으로 끝난 뒤 부른다. 실패해도 이미 발급한 쿠폰은 되돌리지 않는다 (D-113) */
    public void release(Result.Acquired permit) {
        running.remove(permit.token());
        try {
            admissionSlots.release(permit.campaignId(), permit.token());
        } catch (RuntimeException e) {
            close("반환 실패", permit.campaignId(), e);
        }
    }

    /** 처음 여는 명시적 동작 */
    public void open() {
        open.set(true);
    }

    /**
     * 이 인스턴스의 실행이 모두 끝났을 때만 자기 범위의 남은 token 을 지우고 연다. 실행 중이면 닫힌 채로 둔다 (D-160, D-162)
     *
     * @return 다시 열었는가
     */
    public boolean resume(long campaignId) {
        if (!running.isEmpty()) {
            log.warn("실행 중인 발급이 남아 재개하지 않음. running={}", running.size());
            return false;
        }
        String prefix = instanceId + ":";
        admissionSlots.tokens(campaignId).stream()
            .filter(token -> token.startsWith(prefix))
            .forEach(token -> admissionSlots.release(campaignId, token));
        open.set(true);
        return true;
    }

    public boolean isOpen() {
        return open.get();
    }

    private void close(String reason, long campaignId, RuntimeException cause) {
        open.set(false);
        log.error("세마포어 수용을 닫음. 확인 뒤 명시적으로 재개한다. reason={} campaignId={} cause={}", reason, campaignId, cause.toString());
    }
}
