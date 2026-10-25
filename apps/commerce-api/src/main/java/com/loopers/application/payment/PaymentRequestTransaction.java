package com.loopers.application.payment;

import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

// 결제 요청의 트랜잭션 경계다. PaymentRequestFacade가 메서드마다 새 트랜잭션으로 호출한다.
@RequiredArgsConstructor
@Component
public class PaymentRequestTransaction {
    private static final String UNAVAILABLE_PRODUCT = "상품이 삭제되었거나 재고가 부족합니다.";

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final ProviderOrderSuffixGenerator providerOrderSuffixGenerator;

    // TX A: 주문 전이·재고 차감·시도 생성을 함께 commit한다. 하나라도 실패하면 전체를 되돌리고 PG를 호출하지 않는다.
    // 같은 키의 재전송이나 주문 전이 경쟁에서 진 요청은 재고를 차감하기 전에 멈추고 저장된 시도로 응답하게 한다.
    @Transactional
    public PaymentReservation reserve(Long userId, Long orderId, String paymentAttemptKey) {
        Order order = findOwnOrder(userId, orderId);
        Optional<PaymentAttempt> sameKey = paymentAttemptRepository.findByPaymentAttemptKey(paymentAttemptKey);
        if (sameKey.isPresent()) {
            requireSameRequest(sameKey.get(), order);
            return new PaymentReservation.AlreadyRequested();
        }
        order.requestPgPayment();
        lockActiveBrands(order);

        ZonedDateTime now = ZonedDateTime.now();
        if (orderRepository.requestPgPaymentIfDraft(orderId, userId, now) == 0) {
            return new PaymentReservation.AlreadyRequested();
        }
        order.getItems().stream()
            .sorted(Comparator.comparing(OrderItem::getProductId))
            .forEach(item -> {
                if (productRepository.decreaseActiveStock(item.getProductId(), item.getQuantity(), now) == 0) {
                    throw new CoreException(ErrorType.CONFLICT, UNAVAILABLE_PRODUCT);
                }
            });
        PaymentAttempt attempt = paymentAttemptRepository.save(PaymentAttempt.create(
            paymentAttemptKey, orderId, order.getTotalAmount(), providerOrderSuffixGenerator.next()
        ));
        return new PaymentReservation.Started(attempt.getId(), attempt.getProviderOrderId(), attempt.getAmount());
    }

    // TX A가 끝난 뒤 새 읽기 트랜잭션에서 경쟁 요청이 commit한 시도까지 보고 응답을 정한다.
    @Transactional(readOnly = true)
    public PaymentStatusInfo readRequested(Long userId, Long orderId, String paymentAttemptKey) {
        Order order = findOwnOrder(userId, orderId);
        PaymentAttempt attempt = paymentAttemptRepository.findByPaymentAttemptKey(paymentAttemptKey)
            .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, "이미 다른 결제 시도가 있는 주문입니다."));
        requireSameRequest(attempt, order);
        return PaymentStatusInfo.from(order, attempt);
    }

    private Order findOwnOrder(Long userId, Long orderId) {
        return orderRepository.findById(orderId)
            .filter(found -> found.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
    }

    // 요청 일치는 주문 ID와 저장된 주문 금액으로만 판단하고 카드 입력은 비교하지 않는다.
    private static void requireSameRequest(PaymentAttempt attempt, Order order) {
        if (!attempt.matches(order.getId(), order.getTotalAmount())) {
            throw new CoreException(ErrorType.CONFLICT, "결제 시도 키가 다른 결제 요청에 사용되었습니다.");
        }
    }

    // 브랜드 일괄 삭제와 순서를 정하기 위해 활성 Brand를 ID 오름차순으로 공유 잠금한다.
    private void lockActiveBrands(Order order) {
        List<Long> brandIds = order.getItems().stream()
            .map(item -> productRepository.findById(item.getProductId())
                .filter(product -> product.getDeletedAt() == null)
                .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, UNAVAILABLE_PRODUCT)))
            .map(Product::getBrandId)
            .distinct()
            .sorted()
            .toList();
        for (Long brandId : brandIds) {
            brandRepository.findActiveByIdWithSharedLock(brandId)
                .orElseThrow(() -> new CoreException(ErrorType.CONFLICT, UNAVAILABLE_PRODUCT));
        }
    }
}
