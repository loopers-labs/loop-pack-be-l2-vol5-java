package com.loopers.application.activity;

import com.loopers.domain.activity.OrderConfirmation;
import com.loopers.domain.activity.PurchaseActivityService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 구매 활동 저장의 트랜잭션 단위. 완료 기록과 모든 품목 활동이 한 트랜잭션: 중간에 실패하면 전부 rollback 된다.
 * 메서드가 반환하면 commit 이 끝난 것이다 (그 뒤에만 offset 을 ack 한다).
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityProcessor {
    private final PurchaseActivityService purchaseActivityService;

    @Transactional
    public PurchaseActivityService.Outcome record(OrderConfirmation confirmation) {
        return purchaseActivityService.record(confirmation);
    }

    /** 유일 제약 경쟁으로 rollback 된 뒤, 새 트랜잭션에서 먼저 저장된 기록이 같은 사실인지 확인한다. */
    @Transactional(readOnly = true)
    public Optional<PurchaseActivityService.Outcome> checkExisting(OrderConfirmation confirmation) {
        return purchaseActivityService.checkExisting(confirmation);
    }
}
