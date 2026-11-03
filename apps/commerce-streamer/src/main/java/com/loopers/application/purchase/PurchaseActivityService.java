package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmedEvent;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 확정 이벤트 하나를 구매 활동으로 반영한다 (설계 D11). 장부와 모든 품목을 한 TX 에서 commit 하고,
 * 중간 품목 저장이 실패하면 장부·앞선 품목까지 함께 rollback 된다.
 * 리스너와 다른 빈이라 호출마다 새 TX 가 시작된다 (자기 호출로 TX 를 우회하지 않는다).
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityService {
    private final PurchaseActivityRepository purchaseActivityRepository;
    private final Clock clock;

    /**
     * 처리 순서: 장부 조회(eventId, 없으면 orderId) → 있으면 같은 사실인지 비교(no-op 또는 충돌) / 없으면 장부 + 품목 저장.
     * 동시 소비로 UNIQUE 위반이 나면 예외가 그대로 올라가 재시도되고, 재시도의 조회 단계에서 no-op 으로 정리된다.
     */
    @Transactional
    public void consume(OrderConfirmedEvent event) {
        Optional<ProcessedOrderConfirmation> processed = purchaseActivityRepository.findProcessedByEventId(event.eventId())
            .or(() -> purchaseActivityRepository.findProcessedByOrderId(event.orderId()));
        if (processed.isPresent()) {
            processed.get().verifySameFact(event);
            return;
        }

        purchaseActivityRepository.saveProcessed(new ProcessedOrderConfirmation(
            event.eventId(), event.orderId(), event.confirmedAt(), event.fingerprint(), ZonedDateTime.now(clock)));
        for (OrderConfirmedEvent.Item item : event.items()) {
            purchaseActivityRepository.saveActivity(
                new ProductPurchaseActivity(event.orderId(), item.productId(), item.quantity(), event.confirmedAt()));
        }
    }
}
