package com.loopers.payment.application.port.out;

import com.loopers.payment.domain.PaymentOutbox;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * 발송 장부 (ADR-W5-01·03). 상태 전이는 조건부 UPDATE라 여러 실행·여러 서버 중 하나만 이긴다.
 */
public interface PaymentOutboxPort {

    PaymentOutbox save(PaymentOutbox outbox);

    /**
     * PENDING → CLAIMED. 이번 호출이 바꿨을 때만 true (발송 권한).
     */
    boolean claim(Long paymentId, ZonedDateTime now);

    /**
     * PENDING → CLOSED (보내지 않고 닫음). 이번 호출이 바꿨을 때만 true. claim과 경쟁한다.
     */
    boolean close(Long paymentId, ZonedDateTime now);

    /**
     * CLAIMED → DONE (발송 결과 기록 뒤).
     */
    void markDone(Long paymentId, ZonedDateTime now);

    /**
     * 정리 대상 ① createdBefore보다 먼저 저장됐는데 아직 PENDING인 장부의 결제 식별자, 오래된 순.
     */
    List<Long> findPendingCreatedBefore(ZonedDateTime createdBefore, int limit);

    /**
     * 정리 대상 ② claimedBefore보다 먼저 claim했는데 아직 CLAIMED인 장부의 결제 식별자, 오래된 순.
     */
    List<Long> findClaimedBefore(ZonedDateTime claimedBefore, int limit);
}
