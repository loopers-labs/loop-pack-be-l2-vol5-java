package com.loopers.domain.purchase.service;

import com.loopers.domain.purchase.entity.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.entity.ProductPurchaseActivity;
import com.loopers.domain.purchase.enums.PurchaseRecordResult;
import com.loopers.domain.purchase.repository.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.repository.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.vo.OrderConfirmation;
import com.loopers.support.error.RejectReason;
import com.loopers.support.error.RejectedEventException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@RequiredArgsConstructor
@Component
public class PurchaseActivityService {

    private final ProcessedOrderConfirmationRepository processedOrderConfirmationRepository;
    private final ProductPurchaseActivityRepository productPurchaseActivityRepository;

    /**
     * 처음이면 처리 기록과 모든 품목을 한 TX 로 저장함. 기록만 먼저 남기면 활동 없이 "처리함"이 되고,
     * 활동만 먼저 남기면 중복 효과가 생김 (6주차 설계 4.4). 동시 소비는 조회가 아니라 유일 제약이 막음
     */
    @Transactional
    public PurchaseRecordResult record(OrderConfirmation confirmation) {
        Optional<PurchaseRecordResult> judged = judge(confirmation);
        if (judged.isPresent()) {
            return judged.get();
        }
        processedOrderConfirmationRepository.save(new ProcessedOrderConfirmation(confirmation));
        confirmation.items().forEach(item -> productPurchaseActivityRepository.save(
            new ProductPurchaseActivity(confirmation.orderId(), item.productId(), item.quantity())));
        return PurchaseRecordResult.RECORDED;
    }

    /**
     * 기록이 없으면 empty. 같은 사실이면 효과 없이 끝내고, 식별자나 내용이 어긋나면 숨기지 않고 거절함 (D-79, D-81).
     * 전송 정보(topic · offset)는 비교하지 않아 정상 재발행을 충돌로 오판하지 않음
     */
    @Transactional(readOnly = true)
    public Optional<PurchaseRecordResult> judge(OrderConfirmation confirmation) {
        Optional<ProcessedOrderConfirmation> byEventId = processedOrderConfirmationRepository.findByEventId(confirmation.eventId());
        Optional<ProcessedOrderConfirmation> byOrderId = processedOrderConfirmationRepository.findByOrderId(confirmation.orderId());
        if (byEventId.isEmpty() && byOrderId.isEmpty()) {
            return Optional.empty();
        }
        if (byEventId.isEmpty()) {
            // 내용이 같아도 같은 주문의 다른 eventId 는 새 구매로 더하지 않음
            throw new RejectedEventException(RejectReason.CONFLICTING_EVENT_ID,
                "eventId=" + confirmation.eventId() + " orderId=" + confirmation.orderId() + " recordedEventId=" + byOrderId.get().getEventId());
        }
        ProcessedOrderConfirmation processed = byEventId.get();
        if (!processed.getOrderId().equals(confirmation.orderId())) {
            throw new RejectedEventException(RejectReason.CONFLICTING_ORDER,
                "eventId=" + confirmation.eventId() + " orderId=" + confirmation.orderId() + " recordedOrderId=" + processed.getOrderId());
        }
        Map<Long, Integer> recordedItems = productPurchaseActivityRepository.findAllByOrderId(processed.getOrderId()).stream()
            .collect(Collectors.toMap(ProductPurchaseActivity::getProductId, ProductPurchaseActivity::getQuantity));
        if (!processed.hasSameConfirmedAt(confirmation) || !recordedItems.equals(confirmation.quantityByProduct())) {
            throw new RejectedEventException(RejectReason.CONFLICTING_CONTENT,
                "eventId=" + confirmation.eventId() + " confirmedAt=" + confirmation.confirmedAt() + "/" + processed.getConfirmedAt()
                    + " items=" + confirmation.quantityByProduct() + "/" + recordedItems);
        }
        return Optional.of(PurchaseRecordResult.ALREADY_RECORDED);
    }
}
