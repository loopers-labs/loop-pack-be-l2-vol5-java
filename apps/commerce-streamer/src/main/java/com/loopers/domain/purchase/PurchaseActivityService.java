package com.loopers.domain.purchase;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** 주문 확정 사실을 구매 활동으로 저장한다. 트랜잭션은 호출하는 Facade 가 연다. */
@RequiredArgsConstructor
@Component
public class PurchaseActivityService {

    private final ProcessedOrderConfirmationRepository confirmationRepository;
    private final ProductPurchaseActivityRepository activityRepository;

    /** 새로 저장했으면 true, 같은 사실이 이미 저장돼 있어 아무것도 더하지 않았으면 false 다. 다른 사실이면 예외다. */
    public boolean save(OrderConfirmation confirmation) {
        if (isAlreadySaved(confirmation)) {
            return false;
        }
        // 완료 기록을 먼저 flush 해 유일 제약 경쟁이 품목 저장 전에 드러나게 한다.
        confirmationRepository.saveAndFlush(
            new ProcessedOrderConfirmation(confirmation.eventId(), confirmation.orderId(), confirmation.confirmedAt())
        );
        activityRepository.saveAllAndFlush(confirmation.lines().stream()
            .map(line -> new ProductPurchaseActivity(confirmation.orderId(), line, confirmation.confirmedAt()))
            .toList());
        return true;
    }

    /**
     * 같은 사실이 저장돼 있으면 true, 아무것도 없으면 false 다. 조회만 하는 메서드가 아니어서, 이미 있는데 내용이 다르면
     * 예외를 던진다.
     */
    public boolean isAlreadySaved(OrderConfirmation confirmation) {
        List<ProcessedOrderConfirmation> saved =
            confirmationRepository.findByEventIdOrOrderId(confirmation.eventId(), confirmation.orderId());
        if (saved.isEmpty()) {
            return false;
        }
        if (saved.size() > 1 || !isSameConfirmation(saved.get(0), confirmation)) {
            throw new OrderConfirmationConflictException(
                "이미 다른 내용으로 처리한 주문 확정입니다. eventId=" + confirmation.eventId() + ", orderId=" + confirmation.orderId()
            );
        }
        List<PurchaseLine> savedLines = activityRepository.findByOrderId(confirmation.orderId()).stream()
            .map(ProductPurchaseActivity::toLine)
            .sorted(Comparator.comparing(PurchaseLine::productId))
            .toList();
        if (!savedLines.equals(confirmation.lines())) {
            throw new OrderConfirmationConflictException(
                "이미 다른 품목으로 처리한 주문 확정입니다. eventId=" + confirmation.eventId() + ", orderId=" + confirmation.orderId()
            );
        }
        return true;
    }

    private boolean isSameConfirmation(ProcessedOrderConfirmation saved, OrderConfirmation confirmation) {
        return saved.getEventId().equals(confirmation.eventId())
            && saved.getOrderId().equals(confirmation.orderId())
            && saved.getConfirmedAt().toInstant().equals(confirmation.confirmedAt().toInstant());
    }
}
