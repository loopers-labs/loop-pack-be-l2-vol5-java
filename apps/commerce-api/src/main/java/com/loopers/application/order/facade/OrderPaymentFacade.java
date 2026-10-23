package com.loopers.application.order.facade;

import com.loopers.domain.payment.entity.Payment;
import com.loopers.domain.payment.vo.PaymentCreateResult;
import com.loopers.domain.payment.enums.PaymentErrorCode;
import com.loopers.domain.payment.gateway.PaymentGateway;
import com.loopers.domain.payment.gateway.PaymentGatewayRequest;
import com.loopers.domain.payment.vo.PaymentLookupResult;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductListCache;
import com.loopers.support.error.CoreException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;
import com.loopers.application.order.OrderInfo;

/** TX A → TX 밖 PG 호출 → TX B 순서만 정함. PG 호출 중에는 잠금을 쥐지 않고, 생성 요청은 다시 보내지 않음 (D-60) */
@RequiredArgsConstructor
@Component
public class OrderPaymentFacade {
    private final OrderPaymentSteps steps;
    private final PaymentGateway paymentGateway;
    private final ProductListCache productListCache;
    private final ProductDetailCache productDetailCache;

    /** 로그로 새지 않도록 카드 번호를 가림 */
    public record ConfirmCommand(String paymentAttemptKey, String cardType, String cardNo) {
        @Override
        public String toString() {
            return "ConfirmCommand[paymentAttemptKey=" + paymentAttemptKey + ", cardType=" + cardType + ", cardNo=****]";
        }
    }

    /** 발송 권한을 얻은 실행만 PG 생성을 한 번 보냄. 같은 시도 키의 재요청은 저장된 상태만 돌려줌 */
    public OrderInfo confirm(Long userId, Long orderId, ConfirmCommand command) {
        validate(command);
        OrderPaymentSteps.Started started = steps.start(userId, orderId, command.paymentAttemptKey());
        // TX A commit 뒤에 무효화해 rollback 된 확보로 캐시를 지우지 않음
        invalidate(started.soldOutBrandIds(), started.soldOutProductIds());
        if (!started.sendable()) {
            return started.order();
        }
        PaymentCreateResult result = paymentGateway.create(
            new PaymentGatewayRequest(started.providerOrderId(), command.cardType(), command.cardNo(), started.amount()));
        OrderPaymentSteps.Applied applied = steps.applyCreateResult(orderId, result);
        invalidate(applied.restockedBrandIds(), applied.restockedProductIds());
        return applied.order();
    }

    public OrderInfo reconcile(Long userId, Long orderId) {
        return lookupAndApply(steps.snapshot(userId, orderId));
    }

    /** 본문의 상태는 쓰지 않고 조회 결과만 적용함 (D-62) */
    public OrderInfo handleCallback(String providerOrderId) {
        if (isBlank(providerOrderId)) {
            throw new CoreException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
        return lookupAndApply(steps.snapshotByProviderOrderId(providerOrderId));
    }

    /** 미종결 시도만 조회함. 조회 실패면 저장된 상태에 확인 불가를 붙여 돌려줌 */
    private OrderInfo lookupAndApply(OrderPaymentSteps.Snapshot snapshot) {
        if (snapshot.settled()) {
            return snapshot.order();
        }
        PaymentLookupResult result = paymentGateway.findByProviderOrderId(snapshot.providerOrderId());
        if (result instanceof PaymentLookupResult.Unavailable) {
            return snapshot.order().withPaymentUnavailable();
        }
        OrderPaymentSteps.Applied applied = steps.applyLookupResult(snapshot.order().id(), result);
        invalidate(applied.restockedBrandIds(), applied.restockedProductIds());
        return applied.order();
    }

    /** 주문을 다루기 전에 거절함 */
    private static void validate(ConfirmCommand command) {
        Payment.validateAttemptKey(command.paymentAttemptKey());
        if (isBlank(command.cardType()) || isBlank(command.cardNo())) {
            throw new CoreException(PaymentErrorCode.INVALID_PAYMENT_REQUEST);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private void invalidate(Set<Long> brandIds, Set<Long> productIds) {
        brandIds.forEach(productListCache::invalidate);
        productIds.forEach(productDetailCache::invalidate);
    }
}
