package com.loopers.application.activity;

import com.loopers.domain.activity.OrderConfirmation;
import com.loopers.domain.activity.PurchaseActivityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * OrderConfirmed 소비: 상품 구매 활동을 한 번만 남긴다. 트랜잭션 없음: 저장 트랜잭션(PurchaseActivityProcessor)이 끝난 뒤 반환한다.
 * 같은 사실의 재전달은 정상 완료(ALREADY_PROCESSED), 다른 사실이면 OrderConfirmationContractException.
 * 동시 소비로 유일 제약 위반이 나면 그 트랜잭션은 rollback 됐으므로, 새 트랜잭션에서 먼저 저장된 기록과 비교한다.
 * 기록이 없으면(다른 원인의 무결성 위반) 중복으로 바꾸지 않고 원래 예외를 던진다.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PurchaseActivityFacade {
    private final PurchaseActivityProcessor purchaseActivityProcessor;

    public enum Result {
        RECORDED,
        ALREADY_PROCESSED
    }

    public Result consume(PurchaseActivityCommand command) {
        OrderConfirmation confirmation = command.toConfirmation();
        PurchaseActivityService.Outcome outcome;
        try {
            outcome = purchaseActivityProcessor.record(confirmation);
        } catch (DataIntegrityViolationException e) {
            log.info("구매 활동 저장 중 유일 제약 위반, 먼저 저장된 기록과 비교 [eventId = {}]", confirmation.eventId());
            outcome = purchaseActivityProcessor.checkExisting(confirmation).orElseThrow(() -> e);
        }
        return Result.valueOf(outcome.name());
    }
}
