package com.loopers.application.order;

import com.loopers.application.order.dto.ConfirmPreparationResult;
import com.loopers.application.order.dto.OrderConfirmCommand;
import com.loopers.application.order.dto.OrderInfo;
import com.loopers.application.order.dto.OrderItemCommand;
import com.loopers.application.order.dto.OrderSummaryInfo;
import com.loopers.application.payment.PaymentGateway;
import com.loopers.application.payment.PaymentStatusUpdater;
import com.loopers.application.payment.dto.PaymentAttemptInfo;
import com.loopers.application.payment.pg.PgPaymentRequestCommand;
import com.loopers.application.payment.pg.PgPaymentRequestResult;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderLine;
import com.loopers.domain.order.OrderLines;
import com.loopers.domain.order.OrderProduct;
import com.loopers.domain.order.OrderService;
import com.loopers.domain.order.OrderStockService;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptService;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductService;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.paging.PageRequests;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 주문 유스케이스. 요청자 id 는 식별 필터를 거친 값이라 null 이 아니다. {@link #confirm} 만 메소드에 트랜잭션을 열지 않고
 * 구간(TX A, TX B)마다 {@link TransactionTemplate} 으로 감싼다.
 */
@Component
public class OrderFacade {

    private final ProductService productService;
    private final OrderService orderService;
    private final OrderStockService orderStockService;
    private final PaymentAttemptService paymentAttemptService;
    private final PaymentGateway paymentGateway;
    private final PaymentStatusUpdater paymentStatusUpdater;
    private final TransactionTemplate writeTransaction;

    public OrderFacade(
        ProductService productService,
        OrderService orderService,
        OrderStockService orderStockService,
        PaymentAttemptService paymentAttemptService,
        PaymentGateway paymentGateway,
        PaymentStatusUpdater paymentStatusUpdater,
        PlatformTransactionManager transactionManager
    ) {
        this.productService = productService;
        this.orderService = orderService;
        this.orderStockService = orderStockService;
        this.paymentAttemptService = paymentAttemptService;
        this.paymentGateway = paymentGateway;
        this.paymentStatusUpdater = paymentStatusUpdater;
        this.writeTransaction = new TransactionTemplate(transactionManager);
    }

    /**
     * DRAFT 주문을 만든다. 재고는 차감하지 않고 확인만 한다(차감은 확정 때). 검사 순서: 값 규칙(400) → 상품 존재(404) →
     * 재고(409) → 합계 범위(400).
     */
    @Transactional
    public OrderInfo create(Long userId, List<OrderItemCommand> items) {
        OrderLines lines = toLines(items);

        Map<Long, Product> found = productService.getAllActiveByIds(lines.productIds());
        requireAllFound(lines, found);
        requireEnoughStock(lines, found);

        Order order = orderService.create(userId, lines, toOrderProducts(found));
        return OrderInfo.from(order);
    }

    /**
     * 본인의 주문 하나를 조회한다. 없는 주문과 다른 사용자의 주문은 구분하지 않고 NOT_FOUND 다.
     */
    @Transactional(readOnly = true)
    public OrderInfo getMine(Long userId, Long orderId) {
        return OrderInfo.from(orderService.getMine(orderId, userId));
    }

    /**
     * 내 주문 목록을 최신순으로 요약해서 조회한다. 품목은 상세에서 본다.
     */
    @Transactional(readOnly = true)
    public Page<OrderSummaryInfo> getMyList(Long userId, int page, int size) {
        return orderService.getList(userId, PageRequests.of(page, size)).map(OrderSummaryInfo::from);
    }

    /**
     * 주문 확정을 요청한다. 준비(TX A) → PG 요청(TX 밖) → 결과 반영(TX B) 순서이며, 주문은 PG 성공을 확인한 뒤에야
     * CONFIRMED 가 된다. 결과 미확인이어도 create 를 다시 보내지 않는다.
     */
    public PaymentAttemptInfo confirm(OrderConfirmCommand command) {
        ConfirmPreparationResult preparation = prepareConfirm(command);
        if (!preparation.isNewAttempt()) {
            return preparation.info();
        }
        PgPaymentRequestResult result = paymentGateway.requestPayment(preparation.request());
        return writeTransaction.execute(
            status -> paymentStatusUpdater.updateByPaymentRequestResult(preparation.info().orderId(), result)
        );
    }

    /** TX A. PG 호출 전에 주문 잠금, 재고 차감, 시도 저장을 한 번에 commit 한다. 실패하면 전부 rollback 되고 PG 는 호출하지 않는다. */
    public ConfirmPreparationResult prepareConfirm(OrderConfirmCommand command) {
        return writeTransaction.execute(status -> prepare(command));
    }

    // 시도를 새로 저장한 실행만 PG 에 보낸다. 이미 시도가 있으면 같은 키는 저장된 상태를, 다른 키는 충돌을 돌려준다.
    private ConfirmPreparationResult prepare(OrderConfirmCommand command) {
        // 주문 행 잠금이 같은 주문의 동시 요청을 줄 세운다.
        Order order = orderService.getMineForUpdate(command.orderId(), command.userId());

        Optional<PaymentAttempt> existing = paymentAttemptService.findByOrderId(order.getId());
        if (existing.isPresent()) {
            return returnExisting(order, existing.get(), command);
        }
        // 이 주문에 시도가 없는데 같은 키가 있으면 다른 주문의 키다.
        if (paymentAttemptService.findByAttemptKey(command.attemptKey()).isPresent()) {
            throw attemptConflict();
        }

        order.markPaymentPending(); // 이미 확정된 주문은 ORDER_ALREADY_CONFIRMED
        orderStockService.deductStock(order);
        PaymentAttempt attempt = saveAttempt(order, command);
        return new ConfirmPreparationResult(
            PaymentAttemptInfo.of(order, attempt),
            new PgPaymentRequestCommand(
                attempt.getProviderOrderId(), command.cardType(), command.cardNo(), attempt.getAmount().amount()
            )
        );
    }

    // 이미 시도가 있는 주문의 재요청. 새 키를 보내도 두 번째 시도는 만들지 않는다.
    private ConfirmPreparationResult returnExisting(Order order, PaymentAttempt attempt, OrderConfirmCommand command) {
        if (!attempt.getAttemptKey().equals(command.attemptKey())) {
            throw attemptConflict();
        }
        return new ConfirmPreparationResult(PaymentAttemptInfo.of(order, attempt), null);
    }

    // 다른 주문이 같은 키를 동시에 쓰면 유일 제약이 마지막 방어선이다. 이때 차감한 재고도 함께 rollback 된다.
    private PaymentAttempt saveAttempt(Order order, OrderConfirmCommand command) {
        try {
            return paymentAttemptService.request(order.getId(), command.attemptKey(), order.getTotalAmount());
        } catch (DataIntegrityViolationException e) {
            throw attemptConflict();
        }
    }

    private CoreException attemptConflict() {
        return new CoreException(ErrorType.PAYMENT_ATTEMPT_CONFLICT);
    }

    // null 목록과 null 품목은 그대로 OrderLines 에 넘겨, 값 규칙을 한 곳에서 거절한다.
    private OrderLines toLines(List<OrderItemCommand> items) {
        if (items == null) {
            return new OrderLines(null);
        }
        return new OrderLines(
                items.stream().map(item -> item == null ? null : new OrderLine(item.productId(), item.quantity())).toList()
        );
    }

    private Map<Long, OrderProduct> toOrderProducts(Map<Long, Product> products) {
        return products.values().stream().collect(
                Collectors.toMap(Product::getId, product -> new OrderProduct(product.getName(), product.getPrice()))
        );
    }

    // 클라이언트가 어느 상품이 문제인지 알 수 있게 기본 문구 뒤에 상품명을 덧붙인다.
    private CoreException withNames(ErrorType errorType, List<String> names) {
        return new CoreException(errorType, errorType.getMessage() + " (" + String.join(", ", names) + ")");
    }

    // 없거나 삭제된 상품을 요청 순서대로 모두 알려, 클라이언트가 한 번에 빼고 다시 요청할 수 있게 한다.
    private void requireAllFound(OrderLines lines, Map<Long, Product> found) {
        List<Long> missing = lines.productIds().stream().filter(id -> !found.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            String ids = missing.stream().map(String::valueOf).collect(Collectors.joining(", "));
            throw new CoreException(
                    ErrorType.NOT_FOUND, "존재하지 않는 상품이 포함되어 있습니다. (상품 ID: " + ids + ")"
            );
        }
    }

    // 재고가 부족한 상품을 요청 순서대로 모두 알려, 클라이언트가 한 번에 수량을 줄이거나 빼고 다시 요청할 수 있게 한다.
    private void requireEnoughStock(OrderLines lines, Map<Long, Product> found) {
        List<String> shortNames = lines.values().stream()
                .filter(line -> !found.get(line.productId()).hasEnoughStock(line.quantity()))
                .map(line -> found.get(line.productId()).getName())
                .toList();
        if (!shortNames.isEmpty()) {
            throw withNames(ErrorType.OUT_OF_STOCK, shortNames);
        }
    }
}
