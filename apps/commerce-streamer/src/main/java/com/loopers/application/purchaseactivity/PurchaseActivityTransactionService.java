package com.loopers.application.purchaseactivity;

import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.domain.purchaseactivity.ProcessedOrderConfirmationModel;
import com.loopers.domain.purchaseactivity.ProductPurchaseActivityModel;
import com.loopers.domain.purchaseactivity.PurchaseActivityErrorCode;
import com.loopers.domain.purchaseactivity.PurchaseActivityException;
import com.loopers.domain.purchaseactivity.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 구매 활동의 DB TX 경계(소비 설계 5절). 다른 bean 인 PurchaseActivityHandler 가 프록시를 거쳐 호출한다.
 * 완료 기록과 모든 활동은 consume 의 한 TX 다. 완료를 먼저 flush 하는 것은 unique 경쟁을 일찍 받기 위해서이며 별도 commit 이 아니다.
 * 기존 완료·활동은 수정하지 않는다.
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityTransactionService {

    private final PurchaseActivityRepository repository;
    private final PurchaseActivityProbe probe;

    @Transactional
    public PurchaseActivityResult consume(OrderConfirmation event) {
        List<ProcessedOrderConfirmationModel> candidates =
            repository.findProcessedByEventIdOrOrderId(event.eventId(), event.orderId());
        if (!candidates.isEmpty()) {
            requireSameFact(event, candidates);
            return PurchaseActivityResult.ALREADY_PROCESSED;
        }
        // 완료 없이 남은 활동은 부분 저장·수동 손상이다. 새 사건으로 편입하거나 복원하지 않는다.
        if (!repository.findActivitiesByOrderId(event.orderId()).isEmpty()) {
            throw new PurchaseActivityException(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR,
                "완료 기록 없이 구매 활동이 있습니다.");
        }
        repository.insertProcessedAndFlush(ProcessedOrderConfirmationModel.completed(event));
        for (OrderConfirmation.Item item : event.items()) {
            repository.saveActivity(ProductPurchaseActivityModel.purchased(event, item));
            probe.afterActivitySaved(event.orderId(), item.productId());
        }
        probe.beforeCommit(event.orderId());
        return PurchaseActivityResult.APPLIED;
    }

    /**
     * 완료 unique 위반으로 rollback 된 뒤 새 TX 에서 다시 본다. 완료가 없으면 원래 오류를 그대로 전파한다.
     */
    @Transactional(readOnly = true)
    public PurchaseActivityResult verifyDuplicate(OrderConfirmation event, DataIntegrityViolationException originalFailure) {
        List<ProcessedOrderConfirmationModel> candidates =
            repository.findProcessedByEventIdOrOrderId(event.eventId(), event.orderId());
        if (candidates.isEmpty()) {
            throw originalFailure;
        }
        requireSameFact(event, candidates);
        return PurchaseActivityResult.ALREADY_PROCESSED;
    }

    private void requireSameFact(OrderConfirmation event, List<ProcessedOrderConfirmationModel> candidates) {
        // 유효 입력과 정상 저장이면 사건 ID 와 주문 ID 가 서로를 유도하므로 두 행이 나올 수 없다. 입력 충돌이 아니다.
        if (candidates.size() > 1) {
            throw new PurchaseActivityException(PurchaseActivityErrorCode.STORAGE_INTEGRITY_ERROR,
                "사건 ID·주문 ID 에 서로 다른 완료 기록이 있습니다.");
        }
        ProcessedOrderConfirmationModel stored = candidates.get(0);
        event.requireSameFact(stored, repository.findActivitiesByOrderId(stored.getOrderId()));
    }
}
