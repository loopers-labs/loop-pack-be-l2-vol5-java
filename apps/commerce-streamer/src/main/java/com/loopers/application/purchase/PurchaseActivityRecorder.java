package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.OrderEventConflictException;
import com.loopers.domain.purchase.ProcessedOrderConfirmation;
import com.loopers.domain.purchase.ProductPurchaseActivity;
import com.loopers.domain.purchase.PurchaseActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * 구매 활동을 저장하는 짧은 DB 트랜잭션들. {@link PurchaseActivityHandler}와 다른 빈이라 {@code @Transactional}이 프록시를 거쳐 적용된다
 * (같은 객체 안에서 부르면 트랜잭션이 적용되지 않는다).
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityRecorder {

    public enum Result {
        /** 이번 호출이 완료 기록과 모든 품목을 저장했다. */
        RECORDED,
        /** 이미 같은 사실을 반영했다. 정상 no-op이다. */
        ALREADY_PROCESSED
    }

    private final PurchaseActivityRepository repository;

    /**
     * 완료 기록과 모든 품목을 한 트랜잭션으로 저장한다. 중간에 실패하면 앞서 저장한 행도 함께 rollback된다.
     * 먼저 조회하는 것은 흔한 재전달을 빠르게 걸러내려는 것일 뿐이고, 동시 소비의 중복은 DB 유일 제약이 막는다.
     */
    @Transactional
    public Result record(OrderConfirmation confirmation) {
        Optional<Result> existing = existingResult(confirmation);
        if (existing.isPresent()) {
            return existing.get();
        }

        repository.saveProcessed(new ProcessedOrderConfirmation(confirmation));
        for (OrderConfirmation.Item item : confirmation.items()) {
            repository.saveActivity(new ProductPurchaseActivity(confirmation.orderId(), item, confirmation.confirmedAt()));
        }
        return Result.RECORDED;
    }

    /**
     * 유일 제약 경쟁으로 {@link #record}가 rollback된 뒤, <b>새 트랜잭션에서</b> 기존 완료 사실이 같은지 확인한다.
     * 같으면 이미 반영된 것이고, 다르면 충돌이며, 기존 사실이 없으면 비어 있다(다른 원인의 무결성 오류이므로 호출자가 그대로 올린다).
     */
    @Transactional(readOnly = true)
    public Optional<Result> confirmExisting(OrderConfirmation confirmation) {
        return existingResult(confirmation);
    }

    private Optional<Result> existingResult(OrderConfirmation confirmation) {
        Optional<ProcessedOrderConfirmation> byEvent = repository.findProcessedByEventId(confirmation.eventId());
        if (byEvent.isPresent()) {
            if (!byEvent.get().getFingerprint().equals(confirmation.fingerprint())) {
                throw new OrderEventConflictException(
                    "같은 eventId에 다른 주문 사실이 왔습니다. eventId=" + confirmation.eventId() + ", orderId=" + confirmation.orderId());
            }
            return Optional.of(Result.ALREADY_PROCESSED);
        }
        Optional<ProcessedOrderConfirmation> byOrder = repository.findProcessedByOrderId(confirmation.orderId());
        if (byOrder.isPresent()) {
            throw new OrderEventConflictException(
                "같은 주문에 다른 eventId가 왔습니다. orderId=" + confirmation.orderId()
                    + ", 처리한 eventId=" + byOrder.get().getEventId() + ", 받은 eventId=" + confirmation.eventId());
        }
        return Optional.empty();
    }
}
