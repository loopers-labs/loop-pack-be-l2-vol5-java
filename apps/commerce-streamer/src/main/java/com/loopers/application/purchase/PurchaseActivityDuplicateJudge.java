package com.loopers.application.purchase;

import com.loopers.domain.purchase.DuplicateVerdict;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.ProcessedOrderConfirmationRepository;
import com.loopers.domain.purchase.ProductPurchaseActivityRepository;
import com.loopers.domain.purchase.PurchaseFact;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * unique에 걸려 롤백된 뒤, <b>새 트랜잭션</b>에서 저장된 기록을 읽어 판정한다(docs/week6/decisions.md D-10, D-18).
 *
 * 실패한 트랜잭션에서 이어 읽지 않는 이유: 예외가 지난 트랜잭션은 rollback-only이고, 그 트랜잭션의 일반 SELECT는 첫 읽기 때의
 * 스냅샷을 봐서 "있다고 에러가 났는데 찾으면 없다"가 생길 수 있다. 새 트랜잭션의 첫 읽기는 Duplicate를 낸 최신 commit을 본다.
 * 판정 규칙 자체는 domain의 {@link DuplicateVerdict}에 있다.
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityDuplicateJudge {

    private final ProcessedOrderConfirmationRepository processedOrderConfirmationRepository;
    private final ProductPurchaseActivityRepository productPurchaseActivityRepository;

    @Transactional(readOnly = true)
    public DuplicateVerdict judge(OrderConfirmation incoming, PurchaseFact incomingFact) {
        return DuplicateVerdict.judge(incoming, incomingFact,
            processedOrderConfirmationRepository.findByEventId(incoming.eventId()),
            processedOrderConfirmationRepository.findByOrderId(incoming.orderId()),
            productPurchaseActivityRepository.findAllByOrderId(incoming.orderId()));
    }
}
