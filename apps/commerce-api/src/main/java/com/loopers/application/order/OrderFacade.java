package com.loopers.application.order;

import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentCard;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentFinalResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentResultService;
import com.loopers.domain.payment.PaymentStart;
import com.loopers.domain.payment.PaymentStartCommand;
import com.loopers.domain.payment.PaymentStartService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.domain.user.UserRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@RequiredArgsConstructor
@Component
public class OrderFacade {

    private final UserRepository userRepository;
    private final ProductService productService;
    private final OrderService orderService;
    private final PaymentStartService paymentStartService;
    private final PaymentResultService paymentResultService;
    private final PaymentGateway paymentGateway;
    private final PaymentAttemptRepository paymentAttemptRepository;

    /**
     * 생성 시점에는 재고를 차감하지 않고, 상품의 현재 가격을 단가로 기록한다.
     */
    @Transactional
    public OrderInfo createOrder(Long userId, List<OrderCommand.Item> items) {
        requireIdentifiedUser(userId);

        List<OrderItem> orderItems = items.stream()
            .map(this::toOrderItem)
            .toList();

        return OrderInfo.from(orderService.createOrder(userId, orderItems));
    }

    /**
     * 주문 확정 = PG 결제 시작. 포인트는 차감하지 않는다.
     * 이 메서드는 트랜잭션을 갖지 않는다. PG 호출 동안 DB 트랜잭션·락을 보유하지 않기 위해 세 구간으로 나눈다 (설계 1장, 6장).
     * 사용자 식별(401) → 합성 카드 입력 검사(400) → TX A(PaymentStartService.start)
     * → STARTED 면 TX 밖에서 PG create 1회 → 관찰 결과를 TX B(PaymentResultService)에 전달
     *   (ACCEPTED → recordPending, UNKNOWN → recordUnknown, REJECTED_NO_EFFECT → applyFinal FAILED/REJECTED_BEFORE_CREATE)
     * → EXISTING 이면 PG 호출 없이 저장된 상태만 반환
     * → 주문(품목 포함)·시도를 다시 읽어 응답을 만든다.
     * PG 호출의 예외는 잡지 않는다. 포트는 timeout·유실을 UNKNOWN 관찰로 돌려주는 책임이 있고, 그 밖의 예외는 설정 오류다.
     */
    public OrderConfirmInfo confirmOrder(Long requesterId, Long orderId, OrderCommand.Confirm command) {
        requireIdentifiedUser(requesterId);
        PaymentCard card = new PaymentCard(command.cardType(), command.cardNo());

        PaymentStart start = paymentStartService.start(
            requesterId, orderId, new PaymentStartCommand(command.paymentAttemptKey(), card.cardType()));
        if (start.isStarted()) {
            applyCreateResult(start, paymentGateway.create(
                new PaymentCreateCommand(start.providerOrderId(), card.cardType(), card.cardNo(), start.amount())));
        }

        Order order = orderService.getOrderWithItems(orderId);
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "[orderId = " + orderId + "] 결제 시도를 찾을 수 없습니다."));
        return new OrderConfirmInfo(OrderInfo.from(order), attempt.getStatus().name(), attempt.getStatus().isTerminal(), false);
    }

    /**
     * create 응답 한 건을 TX B 에 넘긴다 (설계 1-6). 생성 수락과 미확인은 ⑥만, 효과 0건 확정 거절은 ⑥⑦⑧을 실행한다.
     * 응답의 orderId·amount 가 요청과 달랐으면(결정 ⑱·㉕) UNKNOWN 으로 두고 충돌만 기록한다. transactionKey 는 저장하지 않고 예약은 유지한다.
     */
    private void applyCreateResult(PaymentStart start, PaymentCreateResult result) {
        switch (result.outcome()) {
            case ACCEPTED -> paymentResultService.recordPending(start.attemptId(), result.transactionKey());
            case UNKNOWN -> {
                paymentResultService.recordUnknown(start.attemptId());
                if (result.conflictReason() != null) {
                    paymentResultService.recordConflict(start.attemptId(), result.conflictReason());
                }
            }
            case REJECTED_NO_EFFECT -> paymentResultService.applyFinal(start.attemptId(),
                PaymentFinalResult.failed(PaymentAttempt.FailureReason.REJECTED_BEFORE_CREATE, null));
        }
    }

    @Transactional(readOnly = true)
    public List<OrderInfo> getOrders(Long userId) {
        requireIdentifiedUser(userId);

        return orderService.getOrders(userId).stream()
            .map(OrderInfo::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrder(Long requesterId, Long orderId) {
        return OrderInfo.from(findOwnOrder(requesterId, orderId));
    }

    /**
     * 관리자 조회. ROLE_ADMIN 검사가 접근 경계이므로 소유권을 확인하지 않는다.
     */
    @Transactional(readOnly = true)
    public List<OrderInfo> getOrdersForAdmin(Long userId, int page, int size) {
        return orderService.getOrdersForAdmin(userId, page, size).stream()
            .map(OrderInfo::from)
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderInfo getOrderForAdmin(Long orderId) {
        return OrderInfo.from(orderService.getOrder(orderId));
    }

    private OrderItem toOrderItem(OrderCommand.Item item) {
        Product product = productService.getActiveProduct(item.productId());
        return new OrderItem(item.productId(), item.quantity(), product.getPrice().getAmount());
    }

    /**
     * 남의 주문은 존재를 알리지 않고 없는 것으로 응답한다.
     */
    private Order findOwnOrder(Long requesterId, Long orderId) {
        requireIdentifiedUser(requesterId);

        Order order = orderService.getOrder(orderId);
        if (!order.isOwnedBy(requesterId)) {
            throw new CoreException(ErrorType.NOT_FOUND, "[id = " + orderId + "] 주문을 찾을 수 없습니다.");
        }
        return order;
    }

    private void requireIdentifiedUser(Long userId) {
        if (userId == null || !userRepository.existsById(userId)) {
            throw new CoreException(ErrorType.UNAUTHORIZED, "사용자 식별에 실패했습니다.");
        }
    }
}
