package com.loopers.application.order;

import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentEvent;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentProvider;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final UserService userService;
    private final ProductService productService;
    private final OrderService orderService;
    private final OrderPaymentTransactions paymentTransactions;
    private final PaymentProvider paymentProvider;
    private final PaymentAttemptRepository paymentAttemptRepository;

    @Transactional
    public OrderInfo create(Long userId, List<OrderLine> lines) {
        userService.get(userId);
        Map<Long, Integer> quantities = aggregateQuantities(lines);
        List<OrderItem> items = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : quantities.entrySet()) {
            ProductModel product = productService.get(entry.getKey());
            items.add(new OrderItem(product.getId(), product.getName(), product.getPrice(), entry.getValue()));
        }
        return OrderInfo.from(orderService.create(userId, items));
    }

    /**
     * TX A(재고 확보·시도 기록) → TX 밖 PG 생성 → TX B(결과 저장) → 종결이면 TX C(주문 반영).
     */
    public OrderPaymentInfo confirm(Long userId, Long orderId, PaymentCommand command) {
        command.validate();
        OrderPaymentTransactions.PaymentStart start = paymentTransactions.start(
            userId, orderId, command.attemptKey(), command.cardType(), command.cardNo()
        );
        if (!start.shouldSend()) {
            OrderPaymentInfo existing = applyFinalResult(start.payment());
            return existing.isFinished() ? existing : existing.notChecked();
        }

        OrderPaymentInfo payment = start.payment();
        PaymentRequest request = new PaymentRequest(
            payment.providerOrderId(), command.cardType(), command.cardNo(), payment.amount()
        );
        PaymentObservation observation = paymentProvider.create(request);
        OrderPaymentInfo recorded = applyFinalResult(paymentTransactions.recordCreateResponse(orderId, observation));
        return observation.kind() == PaymentObservation.Kind.UNKNOWN ? recorded.notChecked() : recorded;
    }

    /**
     * 기존 거래를 조회해 결과를 반영한다. 결제 생성 요청은 다시 보내지 않는다.
     * 조회 결과를 확인할 수 없으면(retry 소진·회로 차단) 저장된 상태를 checked=false로 돌려준다.
     */
    public OrderPaymentInfo reconcile(Long userId, Long orderId) {
        return resolve(paymentTransactions.getMine(userId, orderId), PaymentEvent.Source.RECONCILE);
    }

    /** 배치가 오래된 미완료 시도를 reconcile과 같은 규칙으로 마무리한다. 사용자 확인은 하지 않는다. */
    public OrderPaymentInfo recover(Long orderId) {
        return resolve(paymentTransactions.get(orderId), PaymentEvent.Source.BATCH);
    }

    /**
     * PG의 결과 알림. 본문 대신 PG 조회로 TX B에 기록하고 TX C로 주문에 반영한다.
     * 저장된 종결 결과가 있으면 조회 없이 TX C부터 복구한다. 조회 불가면 TX B 없이 UNAVAILABLE이다.
     */
    public CallbackResult handleCallback(String providerOrderId) {
        if (providerOrderId == null || providerOrderId.isBlank()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "PG 주문 번호가 필요합니다.");
        }
        Optional<OrderPaymentInfo> found = paymentTransactions.findByProviderOrderId(providerOrderId);
        if (found.isEmpty()) {
            return CallbackResult.NOT_OURS;
        }
        if (found.get().isFinished()) {
            return CallbackResult.ALREADY_FINISHED;
        }
        return resolve(found.get(), PaymentEvent.Source.CALLBACK).checked()
            ? CallbackResult.CHECKED : CallbackResult.UNAVAILABLE;
    }

    /**
     * 저장 상태에서 시작하는 결과 확인. 완료면 그대로, 결제만 종결이면 PG 조회 없이 TX C,
     * 결과를 모르면 PG 조회 → TX B → 종결이면 TX C. 조회로 확인하지 못하면 checked=false로 저장 상태를 돌려준다.
     */
    private OrderPaymentInfo resolve(OrderPaymentInfo payment, PaymentEvent.Source source) {
        if (payment.isFinished()) {
            return payment;
        }
        if (payment.hasFinalPaymentResult()) {
            return paymentTransactions.applyRecordedResult(payment.order().id());
        }
        PaymentObservation observation = lookup(payment);
        if (observation.kind() == PaymentObservation.Kind.UNKNOWN) {
            return payment.notChecked();
        }
        return applyFinalResult(paymentTransactions.applyLookup(payment.order().id(), observation, source));
    }

    private OrderPaymentInfo applyFinalResult(OrderPaymentInfo payment) {
        return payment.hasFinalPaymentResult()
            ? paymentTransactions.applyRecordedResult(payment.order().id()) : payment;
    }

    private PaymentObservation lookup(OrderPaymentInfo payment) {
        return payment.transactionKey() != null
            ? paymentProvider.findByTransactionKey(payment.transactionKey())
            : paymentProvider.findByOrderId(payment.providerOrderId());
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getMyOrders(Long userId) {
        userService.get(userId);
        List<OrderModel> orders = orderService.getByUser(userId);
        return OrderInfo.from(orders, paymentAttemptRepository.findByOrderIds(orders.stream().map(OrderModel::getId).toList()));
    }

    @Transactional(readOnly = true)
    public OrderInfo getMyOrder(Long userId, Long orderId) {
        userService.get(userId);
        OrderModel order = orderService.get(orderId);
        if (!order.isOwnedBy(userId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다.");
        }
        return OrderInfo.from(order, paymentAttemptRepository.findByOrderId(orderId).orElse(null));
    }

    private static Map<Long, Integer> aggregateQuantities(List<OrderLine> lines) {
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("주문 품목은 하나 이상이어야 합니다.");
        }
        Map<Long, Integer> quantities = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                throw new IllegalArgumentException("주문 품목이 올바르지 않습니다.");
            }
            quantities.merge(line.productId(), line.quantity(), Math::addExact);
        }
        return quantities;
    }

    public record OrderLine(Long productId, int quantity) {}

    public record PaymentCommand(String attemptKey, String cardType, String cardNo) {

        private void validate() {
            if (attemptKey == null || attemptKey.isBlank() || cardType == null || cardType.isBlank()
                || cardNo == null || cardNo.isBlank()) {
                throw new IllegalArgumentException("결제 요청 키와 카드 정보가 필요합니다.");
            }
        }

        @Override
        public String toString() {
            return "PaymentCommand[attemptKey=" + attemptKey + ", card=REDACTED]";
        }
    }

    /** callback 처리 결과. UNAVAILABLE만 PG에 처리하지 못했다고 알린다. */
    public enum CallbackResult { NOT_OURS, ALREADY_FINISHED, CHECKED, UNAVAILABLE }
}
