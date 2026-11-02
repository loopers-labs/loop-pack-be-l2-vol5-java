package com.loopers.domain.activity;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 주문 확정 사실 → 상품 구매 활동. 트랜잭션은 호출하는 쪽(PurchaseActivityProcessor)이 연다.
 * 완료 기록과 모든 품목의 활동을 같은 트랜잭션에서 저장해야 둘 중 하나만 남지 않는다.
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityService {

    public enum Outcome {
        /** 처음 받은 사실. 완료 기록 + 품목별 활동을 저장했다. */
        RECORDED,
        /** 이미 처리한 같은 사실의 재전달. 아무것도 더하지 않았다. */
        ALREADY_PROCESSED
    }

    private final PurchaseActivityRepository purchaseActivityRepository;

    /**
     * 같은 eventId·orderId 의 완료 기록이 있으면 같은 사실인지 확인만 한다 (다르면 계약 위반). 없으면 완료 기록과 품목별 활동을 저장한다.
     * 동시 소비로 조회 뒤 다른 쪽이 먼저 저장하면 유일 제약 위반(DataIntegrityViolationException)이 난다. 이 트랜잭션은 rollback 해야 한다.
     */
    public Outcome record(OrderConfirmation confirmation) {
        Optional<Outcome> existing = checkExisting(confirmation);
        if (existing.isPresent()) {
            return existing.get();
        }
        purchaseActivityRepository.saveConfirmation(new ProcessedOrderConfirmationModel(confirmation));
        for (OrderConfirmation.Line line : confirmation.items()) {
            purchaseActivityRepository.saveActivity(new ProductPurchaseActivityModel(confirmation, line));
        }
        return Outcome.RECORDED;
    }

    /** 이미 처리한 기록이 있으면 같은 사실인지 확인해 ALREADY_PROCESSED, 없으면 empty. 다른 사실이면 계약 위반. */
    public Optional<Outcome> checkExisting(OrderConfirmation confirmation) {
        List<ProcessedOrderConfirmationModel> found =
            purchaseActivityRepository.findConfirmations(confirmation.eventId(), confirmation.orderId());
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (found.size() > 1 || !found.get(0).isSameFactAs(confirmation)) {
            throw new OrderConfirmationContractException("이미 처리한 확정 사실과 다릅니다. [eventId = " + confirmation.eventId()
                + ", orderId = " + confirmation.orderId() + ", existing = " + found.stream()
                .map(f -> f.getEventId() + "/" + f.getOrderId() + "/" + f.getFingerprint()).toList()
                + ", received = " + confirmation.fingerprint() + "]");
        }
        return Optional.of(Outcome.ALREADY_PROCESSED);
    }

    public List<ProductPurchaseActivityModel> getActivities(Long orderId) {
        return purchaseActivityRepository.findActivitiesByOrderId(orderId);
    }
}
