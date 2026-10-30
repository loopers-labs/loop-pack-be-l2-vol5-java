package com.loopers.application.payment;

import com.loopers.application.order.OrderInfo;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.ObservationDecision;
import com.loopers.domain.payment.ObservationSource;
import com.loopers.domain.payment.PaymentCheckToken;
import com.loopers.domain.payment.PaymentConflictModel;
import com.loopers.domain.payment.PaymentConflictRepository;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentObservation;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentRequest;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.product.ProductDetailCache;
import com.loopers.domain.product.ProductModel;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.product.StockChange;
import com.loopers.domain.product.StockHistoryModel;
import com.loopers.domain.product.StockHistoryRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.transaction.AfterCommitRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PG 결제의 로컬 TX 조율자(설계 2.7·4.3). 각 public 메서드가 하나의 짧은 TX 이며 외부 HTTP 를 호출하지 않는다.
 * 호출자(Facade)는 TX 밖에서 이 bean 프록시를 거쳐 부르고, 상위 TX 를 만들지 않는다.
 *
 * <p>모든 업무 TX 의 첫 업무 SQL 은 Order PK 잠금(SELECT FOR UPDATE)이다. 그 뒤에 Payment·PaymentConflict 를 일반 조회하며
 * 자식에는 선행 잠금 조회를 하지 않는다. 같은 주문의 결제 생성·결과 반영·충돌 요약은 이 부모 잠금으로 직렬화한다.
 * 판정 규칙은 PaymentModel·OrderModel 이 갖고 여기서는 순서·저장·재고 반환만 조율한다.
 */
@Component
public class PaymentTransactionService {

    static final String SYNTHETIC_CARD_TYPE = "TEST";
    static final String SYNTHETIC_CARD_NO = "0000-0000-0000-0000";

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentConflictRepository paymentConflictRepository;
    private final ProductRepository productRepository;
    private final StockHistoryRepository stockHistoryRepository;
    private final ProductDetailCache productDetailCache;
    private final AfterCommitRunner afterCommitRunner;
    private final PaymentTransactionProbe probe;
    private final Clock orderConfirmationClock;
    private final ApplicationEventPublisher eventPublisher;
    private final String providerAccount;

    public PaymentTransactionService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                                     PaymentConflictRepository paymentConflictRepository,
                                     ProductRepository productRepository, StockHistoryRepository stockHistoryRepository,
                                     ProductDetailCache productDetailCache, AfterCommitRunner afterCommitRunner,
                                     PaymentTransactionProbe probe,
                                     @Qualifier("orderConfirmationClock") Clock orderConfirmationClock,
                                     ApplicationEventPublisher eventPublisher,
                                     @Value("${commerce.pg.account-id}") String providerAccount) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.paymentConflictRepository = paymentConflictRepository;
        this.productRepository = productRepository;
        this.stockHistoryRepository = stockHistoryRepository;
        this.productDetailCache = productDetailCache;
        this.afterCommitRunner = afterCommitRunner;
        this.probe = probe;
        this.orderConfirmationClock = orderConfirmationClock;
        this.eventPublisher = eventPublisher;
        this.providerAccount = providerAccount;
    }

    /**
     * 콜백 후보 식별. 별도 readOnly TX 에서 orderId 스칼라만 읽고 끝낸다. 0건은 ORDER_NOT_FOUND,
     * 2건 이상은 임의로 고르지 않는 내부 정합성 오류다. 이 TX 의 상태·Entity 를 업무 판정에 쓰지 않는다.
     */
    @Transactional(readOnly = true)
    public Long resolveCallbackOrderId(String providerOrderId) {
        List<Long> orderIds = paymentRepository.findOrderIdsByProviderOrderId(
            PaymentModel.PROVIDER, providerAccount, providerOrderId);
        if (orderIds.isEmpty()) {
            throw new CoreException(ErrorType.ORDER_NOT_FOUND);
        }
        if (orderIds.size() > 1) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "외부 주문 ID 에 결제 후보가 여럿입니다.");
        }
        return orderIds.get(0);
    }

    /** 상태를 바꾸지 않는 사전 준비. 기존 결제면 저장 결과, 결제 없는 DRAFT 면 READY 다. 재고 잠금·발송 기록은 없다. */
    @Transactional
    public PrepareConfirmationResult prepareConfirmation(Long userId, Long orderId, PaymentConfirmCommand command) {
        OrderModel order = lockOwnedOrder(orderId, userId);
        String attemptKey = validateConfirmInput(order, command);
        PaymentModel existing = paymentRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            return PrepareConfirmationResult.stored(storedResult(order, existing, attemptKey));
        }
        requireNewAttempt(order, attemptKey);
        return PrepareConfirmationResult.ready();
    }

    /**
     * TX A. 모든 검사를 잠금 아래 다시 한 뒤 결제 시도 생성·발송 권한 소비·고정 품목 수량 선차감·ORDER_RESERVATION 이력·
     * PAYMENT_PENDING 을 함께 commit 한다. 실패하면 전부 rollback 되고 PG 호출은 없다.
     * 다른 주문의 같은 키는 uk_payment_key, 같은 주문의 중복 생성은 uk_payment_order 가 최종 방어다.
     */
    @Transactional
    public BeginConfirmationResult beginConfirmation(Long userId, Long orderId, PaymentConfirmCommand command) {
        OrderModel order = lockOwnedOrder(orderId, userId);
        String attemptKey = validateConfirmInput(order, command);
        PaymentModel existing = paymentRepository.findByOrderId(orderId).orElse(null);
        if (existing != null) {
            return BeginConfirmationResult.stored(storedResult(order, existing, attemptKey));
        }
        requireNewAttempt(order, attemptKey);

        List<OrderItemModel> items = sortedItems(order);
        Map<Long, ProductModel> products = lockProducts(items, true);
        PaymentModel payment = PaymentModel.start(orderId, attemptKey, providerAccount, order.getTotalAmount(),
            ZonedDateTime.now());
        for (OrderItemModel item : items) {
            ProductModel product = products.get(item.getProductId());
            StockChange change = product.decreaseStock(item.getQuantity());
            stockHistoryRepository.save(StockHistoryModel.reservedByOrder(product.getId(), orderId, change));
            productRepository.save(product);
        }
        order.startPgPayment();
        orderRepository.save(order);
        probe.reach(PaymentTransactionProbe.Point.BEFORE_PAYMENT_INSERT, "beginConfirmation", orderId);
        payment = paymentRepository.save(payment);
        evictAfterCommit(products.keySet());
        reachBeforeCommit("beginConfirmation", orderId);

        PaymentRequest request = new PaymentRequest(payment.getProviderOrderId(), payment.getAmount().toWon(),
            command.cardType(), command.cardNo());
        PaymentCheckToken token = new PaymentCheckToken(payment.getId(), orderId, ObservationSource.CREATE, false);
        return BeginConfirmationResult.send(request, token);
    }

    /**
     * 콜백·대사의 확인 준비. 상태·확인 정보를 바꾸지 않고 토큰과 조회 대상만 복사한다. 고객 요청이면 소유권을 확인한다.
     * 콜백은 알림의 거래 키를 조회 대상으로 쓰되 여기서 결합하지 않고, 대사는 저장 키가 있으면 그 키, 없으면 외부 주문 ID 다.
     */
    @Transactional
    public VerificationTarget prepareVerification(Long orderId, Long requesterUserId, String callbackTransactionKey) {
        OrderModel order = orderRepository.findForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        if (requesterUserId != null) {
            order.requireOwnedBy(requesterUserId);
        }
        PaymentModel payment = paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        requireConsistent(order, payment);
        PaymentCheckToken token = new PaymentCheckToken(payment.getId(), orderId, ObservationSource.QUERY,
            payment.getStatus().isTerminal());
        if (callbackTransactionKey != null) {
            return new VerificationTarget(token, VerificationTarget.LookupKind.TRANSACTION_KEY, callbackTransactionKey);
        }
        if (payment.getTransactionKey() != null) {
            return new VerificationTarget(token, VerificationTarget.LookupKind.TRANSACTION_KEY, payment.getTransactionKey());
        }
        return new VerificationTarget(token, VerificationTarget.LookupKind.PROVIDER_ORDER_ID, payment.getProviderOrderId());
    }

    /**
     * TX B. 잠금 아래 처음 읽은 최신 결제에 관찰을 판정하고, 판정대로 주문 확정·실패 반환·충돌 요약을 같은 TX 에 commit 한다.
     * 실패 반환은 삭제 포함 상품을 ID 오름차순으로 잠가 고정 품목 수량을 현재 재고에 한 번 더한다.
     * 요약 저장을 포함한 어떤 저장이 실패해도 전체 rollback 이며 성공으로 응답하지 않는다.
     */
    @Transactional
    public PaymentApplyResult applyObservation(PaymentCheckToken token, PaymentObservation observation) {
        OrderModel order = orderRepository.findForUpdate(token.orderId())
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "결과 반영 대상 주문이 없습니다."));
        PaymentModel payment = loadTokenPayment(order, token);
        ObservationDecision decision = payment.recordObservation(token, observation, ZonedDateTime.now());

        OrderConfirmed confirmed = null;
        PaymentApplyResult.Outcome outcome = switch (decision.effect()) {
            case CONFIRM -> {
                order.confirmPg(payment.getAmount(), confirmationTime());
                confirmed = OrderConfirmed.from(order);
                yield PaymentApplyResult.Outcome.FINAL;
            }
            case RELEASE -> {
                releaseReservedStock(order);
                order.failPg();
                yield PaymentApplyResult.Outcome.FINAL;
            }
            case FINAL_UNCHANGED -> PaymentApplyResult.Outcome.FINAL;
            case PENDING -> PaymentApplyResult.Outcome.PENDING;
            case UNAVAILABLE -> PaymentApplyResult.Outcome.UNAVAILABLE;
            case CONFLICT -> {
                recordConflictSummary(payment, decision);
                yield PaymentApplyResult.Outcome.CONFLICT;
            }
        };
        orderRepository.save(order);
        paymentRepository.save(payment);
        if (confirmed != null) {
            // 사건을 TX 안에 등록할 뿐 전송이 아니다. commit 전에 실패해 rollback 되면 listener 는 실행되지 않는다.
            eventPublisher.publishEvent(confirmed);
        }
        reachBeforeCommit("applyObservation", order.getId());
        return new PaymentApplyResult(OrderInfo.from(order, payment), outcome);
    }

    /**
     * 결과 반영 TX 가 uk_payment_transaction 위반으로 rollback 된 뒤 새 TX 로 부른다. 같은 Order 잠금 아래 현재 상태를 다시
     * 검사하고 TRANSACTION_ALREADY_BOUND 요약과 이 관찰의 확인 정보를 commit 한다. 업무 상태·저장 키·재고는 바꾸지 않는다.
     */
    @Transactional
    public PaymentApplyResult recordConstraintConflict(PaymentCheckToken token,
                                                       PaymentObservation.TransactionObserved observation) {
        OrderModel order = orderRepository.findForUpdate(token.orderId())
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "충돌 기록 대상 주문이 없습니다."));
        PaymentModel payment = loadTokenPayment(order, token);
        ObservationDecision decision = payment.recordTransactionAlreadyBound(token, observation, ZonedDateTime.now());
        recordConflictSummary(payment, decision);
        paymentRepository.save(payment);
        reachBeforeCommit("recordConstraintConflict", order.getId());
        return new PaymentApplyResult(OrderInfo.from(order, payment), PaymentApplyResult.Outcome.CONFLICT);
    }

    /**
     * 최초 CONFIRM 분기에서만 부른다. 같은 종결의 반복(FINAL_UNCHANGED)은 확정 시각을 다시 만들지 않는다.
     * DATETIME(6) 의 저장 정밀도와 맞춰 저장 전후 값이 같도록 마이크로초로 절삭한다.
     */
    private ZonedDateTime confirmationTime() {
        return ZonedDateTime.now(orderConfirmationClock)
            .withZoneSameInstant(ZoneOffset.UTC)
            .truncatedTo(ChronoUnit.MICROS);
    }

    private OrderModel lockOwnedOrder(Long orderId, Long userId) {
        OrderModel order = orderRepository.findForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        order.requireOwnedBy(userId);
        return order;
    }

    /** 과거 POINT 확정은 카드 검증보다 먼저 거절한다. 시도 키는 정규화한 값을 돌려준다. */
    private String validateConfirmInput(OrderModel order, PaymentConfirmCommand command) {
        if (order.getPaymentMethod() == PaymentMethod.POINT) {
            throw new CoreException(ErrorType.ORDER_NOT_CONFIRMABLE);
        }
        String attemptKey = PaymentModel.normalizeAttemptKey(command.paymentAttemptKey());
        if (!SYNTHETIC_CARD_TYPE.equals(command.cardType()) || !SYNTHETIC_CARD_NO.equals(command.cardNo())) {
            throw new CoreException(ErrorType.INVALID_REQUEST, "고정 합성 카드만 사용할 수 있습니다.");
        }
        return attemptKey;
    }

    /** 같은 주문·같은 키의 기존 결제만 저장 결과로 돌려주고, 다른 키는 충돌이다. 저장 값 불일치는 내부 정합성 오류다. */
    private OrderInfo storedResult(OrderModel order, PaymentModel payment, String attemptKey) {
        if (!payment.getAttemptKey().equals(attemptKey)) {
            throw new CoreException(ErrorType.PAYMENT_ATTEMPT_CONFLICT);
        }
        requireConsistent(order, payment);
        return OrderInfo.from(order, payment);
    }

    private void requireNewAttempt(OrderModel order, String attemptKey) {
        order.requireConfirmable();
        if (order.getPaymentMethod() == PaymentMethod.PG) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 결제 표시 주문에 결제 시도가 없습니다.");
        }
        order.requireStoredItemsConsistent();
        if (paymentRepository.existsByAttemptKey(attemptKey)) {
            throw new CoreException(ErrorType.PAYMENT_ATTEMPT_CONFLICT);
        }
    }

    /** 주문·결제의 저장 연결과 상태 결합을 검사한다(설계 3.6 TX B 검사). 어긋나면 임의로 처리하지 않는다. */
    private void requireConsistent(OrderModel order, PaymentModel payment) {
        order.requireStoredItemsConsistent();
        boolean linked = payment.getOrderId().equals(order.getId())
            && PaymentModel.PROVIDER.equals(payment.getProvider())
            && providerAccount.equals(payment.getProviderAccount())
            && PaymentModel.providerOrderIdOf(order.getId()).equals(payment.getProviderOrderId())
            && payment.getAmount().equals(order.getTotalAmount())
            && order.getPaymentMethod() == PaymentMethod.PG
            && Long.valueOf(0L).equals(order.getPointAmount());
        // POINT 주문은 linked 에서 이미 걸러지므로 아래 확정 시각 조건은 PG 주문에만 적용된다.
        boolean statusMatches = switch (order.getStatus()) {
            case PAYMENT_PENDING -> (payment.getStatus() == PaymentStatus.UNKNOWN || payment.getStatus() == PaymentStatus.PENDING)
                && order.getPaidAmount() == null && order.getConfirmedAt() == null;
            case CONFIRMED -> payment.getStatus() == PaymentStatus.SUCCESS && payment.getAmount().equals(order.getPaidAmount())
                && OrderModel.isUtcMicros(order.getConfirmedAt());
            case PAYMENT_FAILED -> payment.getStatus() == PaymentStatus.FAILED && order.getPaidAmount() == null
                && order.getConfirmedAt() == null;
            case DRAFT -> false;
        };
        if (!linked || !statusMatches) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "주문과 결제 시도의 저장 상태가 맞지 않습니다. [orderId = "
                + order.getId() + "]");
        }
    }

    private PaymentModel loadTokenPayment(OrderModel order, PaymentCheckToken token) {
        PaymentModel payment = paymentRepository.findByOrderId(order.getId())
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "결과 반영 대상 결제가 없습니다."));
        if (!payment.getId().equals(token.paymentId())) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "확인 토큰의 결제가 주문의 결제와 다릅니다.");
        }
        requireConsistent(order, payment);
        return payment;
    }

    private void releaseReservedStock(OrderModel order) {
        List<OrderItemModel> items = sortedItems(order);
        Map<Long, ProductModel> products = lockProducts(items, false);
        for (OrderItemModel item : items) {
            ProductModel product = products.get(item.getProductId());
            StockChange change = product.releaseStock(item.getQuantity());
            stockHistoryRepository.save(StockHistoryModel.releasedByOrder(product.getId(), order.getId(), change));
            productRepository.save(product);
        }
        evictAfterCommit(products.keySet());
    }

    /** 부모 Order 잠금 아래 기존 요약을 일반 조회해 없으면 만들고 있으면 이 TX 의 충돌로 교체한다. */
    private void recordConflictSummary(PaymentModel payment, ObservationDecision decision) {
        ZonedDateTime detectedAt = ZonedDateTime.now();
        PaymentConflictModel conflict = paymentConflictRepository.findByPaymentId(payment.getId()).orElse(null);
        if (conflict == null) {
            probe.reach(PaymentTransactionProbe.Point.BEFORE_CONFLICT_INSERT, "recordConflictSummary", payment.getOrderId());
            conflict = PaymentConflictModel.first(payment.getId(), decision.conflictCode(), decision.evidence(), detectedAt);
        } else {
            conflict.record(decision.conflictCode(), decision.evidence(), detectedAt);
        }
        paymentConflictRepository.save(conflict);
    }

    private List<OrderItemModel> sortedItems(OrderModel order) {
        List<OrderItemModel> items = order.getItems().stream()
            .sorted(Comparator.comparing(OrderItemModel::getProductId))
            .toList();
        if (items.isEmpty()) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "주문 품목이 없습니다.");
        }
        return items;
    }

    /**
     * 중복 제거·ID 오름차순으로 잠근다. 확보(TX A)는 활성 상품만, 반환은 삭제 포함이다. 확보의 누락은 PRODUCT_NOT_FOUND,
     * 반환의 물리 누락은 내부 정합성 오류다.
     */
    private Map<Long, ProductModel> lockProducts(List<OrderItemModel> items, boolean activeOnly) {
        List<Long> productIds = items.stream().map(OrderItemModel::getProductId).distinct().sorted().toList();
        List<ProductModel> locked = activeOnly
            ? productRepository.findAllActiveByIdsForUpdate(productIds)
            : productRepository.findAllByIdsForUpdate(productIds);
        Map<Long, ProductModel> products = locked.stream()
            .collect(Collectors.toMap(ProductModel::getId, Function.identity()));
        for (Long productId : productIds) {
            if (!products.containsKey(productId)) {
                throw activeOnly
                    ? new CoreException(ErrorType.PRODUCT_NOT_FOUND, "[productId = " + productId + "]")
                    : new CoreException(ErrorType.INTERNAL_ERROR, "반환할 상품이 없습니다. [productId = " + productId + "]");
            }
        }
        return products;
    }

    private void evictAfterCommit(Collection<Long> productIds) {
        List<Long> changed = List.copyOf(productIds);
        afterCommitRunner.runAfterCommit("상품 상세 기본정보 무효화", () -> productDetailCache.evictAll(changed));
    }

    /**
     * 직접 flush 하지 않는다. 제약 위반은 commit 때 Spring 이 DataIntegrityViolationException 으로 바꿔 호출자에게 전하므로,
     * 여기서 flush 하면 같은 위반이 변환되지 않은 Hibernate 예외로 새어 나간다(I3-2 검증에서 확인).
     */
    private void reachBeforeCommit(String operation, Long orderId) {
        probe.reach(PaymentTransactionProbe.Point.BEFORE_COMMIT, operation, orderId);
    }
}
