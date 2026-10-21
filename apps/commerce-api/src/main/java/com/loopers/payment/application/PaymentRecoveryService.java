package com.loopers.payment.application;

import com.loopers.payment.application.port.in.PaymentRecoveryUseCase;
import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentApplyOutcome;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.function.Function;

/**
 * 정리·reconcile·만료의 판단 (plan.md 5-1, ADR-W5-01·03, P-14). 스케줄러는 언제 부를지만 알고, 무엇을 어떻게 닫고 확인할지는 여기와 PaymentResultApplier가 안다.
 * 한 건의 실패가 다음 건을 막지 않도록 건마다 따로 처리하고 기록한다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentRecoveryService implements PaymentRecoveryUseCase {

    /** 발송 이벤트를 잃었다고 볼 대기 시간. 평소 발송은 수 ms 안에 claim한다 */
    static final Duration STALE_AFTER = Duration.ofSeconds(30);
    /** P-14: 보낸 뒤 이만큼 지나도 결과를 모르면 FAILED(EXPIRED)로 닫고 재고를 돌려준다 */
    static final Duration EXPIRE_AFTER = Duration.ofMinutes(30);
    static final int CLOSE_PAGE_SIZE = 100;
    /** 한 번 실행에서 끝까지 비우되, 계속 실패하는 행 때문에 멈추지 않도록 쪽 수를 제한한다 */
    static final int MAX_CLOSE_PAGES = 10;
    static final int RECONCILE_PAGE_SIZE = 50;

    private final PaymentOutboxPort paymentOutboxPort;
    private final PaymentPort paymentPort;
    private final PaymentResultApplier paymentResultApplier;

    @Override
    public StaleDispatchResult closeStaleDispatches(ZonedDateTime now) {
        ZonedDateTime staleBefore = now.minus(STALE_AFTER);
        int closed = drain(
            limit -> paymentOutboxPort.findPendingCreatedBefore(staleBefore, limit),
            paymentResultApplier::closeUnclaimed,
            "보내지 않은 결제 정리"
        );
        int unknown = drain(
            limit -> paymentOutboxPort.findClaimedBefore(staleBefore, limit),
            paymentResultApplier::recordUnknown,
            "멈춘 발송을 UNKNOWN으로"
        );
        if (closed > 0 || unknown > 0) {
            log.warn("발송 정리: FAILED(NOT_SENT) {}건, UNKNOWN {}건", closed, unknown);
        }
        return new StaleDispatchResult(closed, unknown);
    }

    @Override
    public int reconcileDue(ZonedDateTime now) {
        List<Payment> due = paymentPort.findDueForCheck(now, RECONCILE_PAGE_SIZE);
        for (Payment payment : due) {
            try {
                paymentResultApplier.reconcile(payment.getId());
            } catch (RuntimeException e) {
                log.error("reconcile 실패 — 다음 주기에 다시 봅니다. paymentId={}", payment.getId(), e);
            }
        }
        return due.size();
    }

    @Override
    public int expireOverdue(ZonedDateTime now) {
        ZonedDateTime sentBefore = now.minus(EXPIRE_AFTER);
        int expired = drain(
            limit -> paymentPort.findExpirable(sentBefore, limit),
            this::expireAfterLastLookup,
            "보낸 뒤 30분 미해결 결제 만료"
        );
        if (expired > 0) {
            log.warn("보낸 뒤 30분 넘게 결과를 모르는 결제 {}건을 FAILED(EXPIRED)로 닫고 재고를 반환했습니다", expired);
        }
        return expired;
    }

    /**
     * 닫기 전에 PG를 마지막으로 한 번 조회한다(잠금 밖). 그 조회로 종결됐으면 만료하지 않는다.
     */
    private PaymentApplyOutcome expireAfterLastLookup(Long paymentId) {
        paymentResultApplier.reconcile(paymentId);
        boolean resolved = paymentPort.findById(paymentId).map(Payment::isFinal).orElse(true);
        if (resolved) {
            return PaymentApplyOutcome.ALREADY_APPLIED;
        }
        return paymentResultApplier.expire(paymentId);
    }

    /**
     * 쪽이 가득 찼으면 곧바로 다음 쪽을 본다. 이번 호출이 상태를 바꾼(APPLIED·NOT_FINAL) 건수를 돌려준다.
     */
    private int drain(Function<Integer, List<Long>> page, Function<Long, PaymentApplyOutcome> action, String job) {
        int changed = 0;
        for (int pageNo = 0; pageNo < MAX_CLOSE_PAGES; pageNo++) {
            List<Long> paymentIds = page.apply(CLOSE_PAGE_SIZE);
            for (Long paymentId : paymentIds) {
                try {
                    PaymentApplyOutcome outcome = action.apply(paymentId);
                    if (outcome == PaymentApplyOutcome.APPLIED || outcome == PaymentApplyOutcome.NOT_FINAL) {
                        changed++;
                    }
                } catch (RuntimeException e) {
                    log.error("{} 실패 — 다음 주기에 다시 봅니다. paymentId={}", job, paymentId, e);
                }
            }
            if (paymentIds.size() < CLOSE_PAGE_SIZE) {
                break;
            }
        }
        return changed;
    }
}
