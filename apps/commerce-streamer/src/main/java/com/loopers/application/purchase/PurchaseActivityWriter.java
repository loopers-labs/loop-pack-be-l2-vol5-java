package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.PurchaseFact;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;

/**
 * 처리 기록과 그 이벤트의 모든 구매 활동을 한 트랜잭션에서 남긴다(INV-604). 하나라도 실패하면 전부 롤백된다 —
 * 처리 기록만 먼저 commit하고 죽으면 재전달이 "이미 처리"로 건너뛰어 활동이 빠진다.
 *
 * - 조회 후 insert가 아니라 바로 insert한다. 겹침은 DB unique가 판정한다(INV-605, docs/week6/decisions.md D-10).
 * - 처리 기록을 먼저 넣는다. 같은 이벤트를 동시에 처리하는 두 번째 쪽은 event_id unique 락에서 첫 쪽이 끝나길 기다린다.
 * - unique 예외는 여기서 잡지 않는다. 예외가 경계를 지나 이 트랜잭션은 롤백되고, 비교는 호출자가 새 트랜잭션에서 한다(D-10).
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityWriter {

    private final ProcessedOrderConfirmationRepository processedOrderConfirmationRepository;
    private final ProductPurchaseActivityRepository productPurchaseActivityRepository;

    @Transactional
    public void write(OrderConfirmation confirmation, PurchaseFact fact) {
        processedOrderConfirmationRepository.insert(new ProcessedOrderConfirmation(confirmation, fact, ZonedDateTime.now()));
        for (OrderConfirmation.Item item : confirmation.items()) {
            productPurchaseActivityRepository.insert(
                new ProductPurchaseActivity(confirmation.orderId(), item, confirmation.confirmedAt()));
        }
    }
}
