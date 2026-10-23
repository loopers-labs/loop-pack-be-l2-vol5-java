package com.loopers.domain.payment;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Collection;

/**
 * 수신함 행의 저장과 처리 상태 기록. 각각 짧은 트랜잭션이다 — PG 조회와 결과 반영(TX B)은 이 트랜잭션 밖에서 끝난 뒤다.
 * 반영은 됐는데 이 기록이 실패하면 행이 다음 회차에 다시 꺼내지지만, 다시 조회해도 INV-002·003이 효과를 막는다.
 */
@RequiredArgsConstructor
@Component
public class PaymentCallbackInboxService {

    private final PaymentCallbackInboxRepository paymentCallbackInboxRepository;

    @Transactional
    public PaymentCallbackInbox receive(String rawPayload, String transactionKey, String providerOrderId, ZonedDateTime now) {
        return paymentCallbackInboxRepository.save(new PaymentCallbackInbox(rawPayload, transactionKey, providerOrderId, now));
    }

    @Transactional
    public void markUnmatched(Collection<Long> inboxIds, ZonedDateTime now) {
        paymentCallbackInboxRepository.findAllByIdIn(inboxIds).forEach(inbox -> inbox.unmatched(now));
    }

    @Transactional
    public void markDone(Collection<Long> inboxIds, Long attemptId, ZonedDateTime now) {
        paymentCallbackInboxRepository.findAllByIdIn(inboxIds).forEach(inbox -> inbox.done(attemptId, now));
    }

    @Transactional
    public void markFailed(Collection<Long> inboxIds, Long attemptId, String error, ZonedDateTime now, ZonedDateTime nextTryAt) {
        paymentCallbackInboxRepository.findAllByIdIn(inboxIds).forEach(inbox -> inbox.failed(attemptId, error, now, nextTryAt));
    }
}
