package com.loopers.application.order;

import com.loopers.application.product.ProductQueryException;
import com.loopers.application.user.UserResolutionException;
import com.loopers.application.user.UserResolver;
import com.loopers.domain.brand.Brand;
import com.loopers.domain.brand.BrandRepository;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderConfirmedEvent;
import com.loopers.domain.order.OrderException;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.OrderPaymentMethod;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.domain.payment.PaymentAttemptStatus;
import com.loopers.domain.payment.PaymentCreateRequest;
import com.loopers.domain.payment.PaymentFingerprintInput;
import com.loopers.domain.payment.PaymentObservationResult;
import com.loopers.domain.payment.PaymentProviderUnavailableException;
import com.loopers.domain.payment.PaymentRequestConflictException;
import com.loopers.domain.payment.PaymentRequestFingerprinter;
import com.loopers.domain.payment.PaymentRecoveryException;
import com.loopers.domain.payment.PaymentTransaction;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductListRevisionRepository;
import com.loopers.domain.product.ProductRepository;
import com.loopers.domain.user.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * PG HTTP와 분리한 내부 TX A/B 경계. 요청 비교와 신규 지문 저장도 같은 주문 잠금 안에서 수행한다.
 */
@Service
@Slf4j
@Transactional(isolation = Isolation.READ_COMMITTED)
public class OrderPaymentStockService {
    private final UserResolver userResolver;
    private final UserRepository users;
    private final OrderRepository orders;
    private final BrandRepository brands;
    private final ProductRepository products;
    private final PaymentAttemptRepository attempts;
    private final ProductListRevisionRepository revisions;
    private final PaymentRequestFingerprinter fingerprinter;
    private final ApplicationEventPublisher events;
    private final Clock orderPaymentClock;

    public OrderPaymentStockService(UserResolver userResolver, UserRepository users, OrderRepository orders,
                                    BrandRepository brands, ProductRepository products,
                                    PaymentAttemptRepository attempts, ProductListRevisionRepository revisions,
                                    PaymentRequestFingerprinter fingerprinter, ApplicationEventPublisher events,
                                    @Qualifier("orderPaymentClock") Clock orderPaymentClock) {
        this.userResolver = userResolver;
        this.users = users;
        this.orders = orders;
        this.brands = brands;
        this.products = products;
        this.attempts = attempts;
        this.revisions = revisions;
        this.fingerprinter = fingerprinter;
        this.events = events;
        this.orderPaymentClock = orderPaymentClock;
    }

    /**
     * 신규 DRAFT 전용 준비다. 재요청은 prepareOrReuse를 사용한다. 두 경로 모두 지문을 필수 저장한다.
     * REQUIRED이므로 외부 TX에 참여했다면 반환 시점도 아직 커밋 전이다. 이 안에서 PG를 호출하지 않는다.
     */
    public PreparedPayment prepareNew(String requester, long orderId, UUID attemptKey,
                                      String providerAccount, String providerOrderId, String cardType, String cardNo) {
        long userId = userResolver.resolve(requester).userId();
        Order order = lockOrder(orderId);
        order.requireOwner(userId);
        return prepareDraft(order, userId, attemptKey, providerAccount, providerOrderId, cardType, cardNo);
    }

    /**
     * 같은 주문의 재요청은 주문→시도만 잠그고 저장 상태를 반환한다. 삭제/가격/재고의 현재값으로 재검증하지 않는다.
     * dispatch가 있는 반환도 프록시 TX 커밋 뒤에만 발송 가능하며 재요청에는 발송 권한을 다시 주지 않는다.
     */
    public PaymentPreparation prepareOrReuse(String requester, long orderId, UUID attemptKey,
                                             String providerAccount, String providerOrderId, String cardType, String cardNo) {
        return prepareOrReuse(requester, orderId, attemptKey, providerAccount, providerOrderId, cardType, cardNo, false);
    }

    /** The public API never accepts providerOrderId; an existing attempt always keeps its stored identity. */
    public PaymentPreparation prepareCustomer(String requester, long orderId, UUID attemptKey,
                                               String providerAccount, String cardType, String cardNo) {
        return prepareOrReuse(requester, orderId, attemptKey, providerAccount, null, cardType, cardNo, true);
    }

    /** Existing requests do not need a live PG adapter. This entry can never prepare or dispatch a new payment. */
    public AppliedPayment reuseCustomer(String requester, long orderId, UUID attemptKey, String cardType, String cardNo) {
        long userId = userResolver.resolve(requester).userId();
        Order order = lockOrder(orderId);
        order.requireOwner(userId);
        if (order.getPaymentMethod() == OrderPaymentMethod.POINT) {
            order.requireDraft();
        }
        if (order.getStatus() == OrderStatus.DRAFT) {
            throw new PaymentProviderUnavailableException();
        }
        PaymentAttempt attempt = storedAttempt(order);
        return compareExisting(order, userId, attempt, attemptKey, attempt.getProviderAccount(),
            attempt.getProviderOrderId(), cardType, cardNo);
    }

    private PaymentPreparation prepareOrReuse(String requester, long orderId, UUID attemptKey,
                                               String providerAccount, String providerOrderId, String cardType,
                                               String cardNo, boolean serverIdentity) {
        long userId = userResolver.resolve(requester).userId();
        Order order = lockOrder(orderId);
        order.requireOwner(userId);
        if (order.getPaymentMethod() == OrderPaymentMethod.POINT) {
            order.requireDraft();
        }
        if (order.getStatus() == OrderStatus.DRAFT) {
            if (serverIdentity) {
                providerOrderId = ProviderOrderId.resolve(orderId, null);
            }
            var prepared = prepareDraft(order, userId, attemptKey, providerAccount, providerOrderId, cardType, cardNo);
            return new PaymentPreparation(prepared,
                new AppliedPayment(OrderStatus.PAYMENT_PENDING, PaymentAttemptStatus.NOT_OBSERVED, PaymentObservationResult.UNCHANGED));
        }
        PaymentAttempt attempt = storedAttempt(order);
        if (serverIdentity) {
            providerOrderId = ProviderOrderId.resolve(orderId, attempt);
        }
        return new PaymentPreparation(null,
            compareExisting(order, userId, attempt, attemptKey, providerAccount, providerOrderId, cardType, cardNo));
    }

    private PaymentAttempt storedAttempt(Order order) {
        order.validatePgPaymentState();
        PaymentAttempt attempt = attempts.lockByOrderId(order.getId())
            .orElseThrow(() -> new IllegalStateException("Prepared order has no payment attempt"));
        validateStoredAttempt(order, attempt);
        return attempt;
    }

    private AppliedPayment compareExisting(Order order, long userId, PaymentAttempt attempt, UUID attemptKey,
                                             String providerAccount, String providerOrderId, String cardType, String cardNo) {
        validateRequest(attemptKey, providerAccount, providerOrderId, cardType, cardNo);
        if (!attempt.getAttemptKey().equals(attemptKey) || !attempt.getProviderAccount().equals(providerAccount)
            || !attempt.getProviderOrderId().equals(providerOrderId)) {
            throw new PaymentRequestConflictException();
        }
        var input = new PaymentFingerprintInput(userId, order.getId(), order.getTotalAmount(), cardType, cardNo);
        if (!fingerprinter.matches(input, attempt.requireRequestFingerprint())) {
            throw new PaymentRequestConflictException();
        }
        return snapshot(order, attempt, PaymentObservationResult.UNCHANGED);
    }

    private PreparedPayment prepareDraft(Order order, long userId, UUID attemptKey,
                                          String providerAccount, String providerOrderId, String cardType, String cardNo) {
        order.requireDraft();
        order.validateStoredQuantities();
        validateRequest(attemptKey, providerAccount, providerOrderId, cardType, cardNo);
        users.lockById(userId).orElseThrow(UserResolutionException::userNotFound);
        Map<Long, Integer> quantities = quantities(order);
        Map<Long, Product> locked = lockProducts(quantities.keySet(), false);
        if (attempts.lockByOrderId(order.getId()).isPresent()) {
            throw new IllegalStateException("A prepared payment attempt already exists");
        }
        // 다른 주문 행을 역순 잠그지 않는다. 조기 확인을 통과한 동시 요청도 DB UUID 유일 제약이 최종 보호한다.
        if (attempts.findByAttemptKey(attemptKey).isPresent()) {
            throw new PaymentRequestConflictException();
        }
        locked.forEach((id, product) -> product.validateStockDeduction(quantities.get(id)));
        // 저장 금액·모든 품목의 상태를 검증한 뒤에만 실제 가용 재고를 바꾼다.
        order.preparePgPayment();
        var fingerprint = fingerprinter.calculate(new PaymentFingerprintInput(userId, order.getId(),
            order.getTotalAmount(), cardType, cardNo));
        PaymentAttempt attempt = new PaymentAttempt(order.getId(), attemptKey, providerAccount, providerOrderId,
            order.getTotalAmount(), fingerprint);
        locked.forEach((id, product) -> product.deductStock(quantities.get(id)));
        attempt.startDispatch(ZonedDateTime.now(orderPaymentClock));
        attempts.save(attempt);
        revisions.advance();
        return new PreparedPayment(order.getId(), attemptKey, providerAccount, providerOrderId, order.getTotalAmount());
    }

    private void validateRequest(UUID attemptKey, String providerAccount, String providerOrderId,
                                  String cardType, String cardNo) {
        if (attemptKey == null || providerAccount == null || providerAccount.isBlank()) {
            throw new IllegalArgumentException("Payment attempt identities are required");
        }
        // Only validate transient input here. Order validates the real stored amount as state, not client input.
        new PaymentCreateRequest(providerOrderId, 0, cardType, cardNo);
    }

    /** 신뢰한 adapter의 생성 응답·권위 조회 전용이다. raw callback을 전달하면 안 된다. */
    public AppliedPayment apply(long orderId, UUID attemptKey, PaymentTransaction observed) {
        return observe(orderId, attemptKey, attempt -> attempt.observe(observed));
    }

    public AppliedPayment markUnknown(long orderId, UUID attemptKey) {
        return observe(orderId, attemptKey, PaymentAttempt::markUnknown);
    }

    /** 복수 거래/adapter 충돌을 임의의 한 거래로 적용하지 않고 현재 저장 결과와 충돌 판정만 반환한다. */
    public AppliedPayment preserveConflict(long orderId, UUID attemptKey) {
        return observe(orderId, attemptKey, attempt -> PaymentObservationResult.CONFLICT);
    }

    /**
     * 외부 조회에 쓸 불변 식별자와 저장 상태만 분리한다. 주문 잠금으로 TX B와 일관된 snapshot을 읽는다.
     * 재고를 바꾸지 않으므로 상품 잠금은 필요 없고 주문→시도의 상대 잠금 순서는 유지한다.
     */
    public StoredPayment inspectPrepared(String requester, long orderId, UUID attemptKey) {
        long userId = userResolver.resolve(requester).userId();
        Order order = lockOrder(orderId);
        order.requireOwner(userId);
        order.validatePgPaymentState();
        PaymentAttempt attempt = attempts.lockByOrderId(orderId)
            .orElseThrow(() -> new IllegalStateException("Prepared order has no payment attempt"));
        validateStoredAttempt(order, attempt);
        if (!attempt.getAttemptKey().equals(attemptKey)) {
            throw new IllegalArgumentException("Payment attempt does not match the stored order");
        }
        return new StoredPayment(attempt.getProviderAccount(), attempt.getProviderOrderId(),
            attempt.getTransactionKey(), snapshot(order, attempt, PaymentObservationResult.UNCHANGED));
    }

    /** Ownership is checked before disclosing whether the order has a PG attempt; no card/fingerprint is needed. */
    public RecoveryPayment inspectCustomerRecovery(String requester, long orderId) {
        long userId = userResolver.resolve(requester).userId();
        Order order = lockOrder(orderId);
        order.requireOwner(userId);
        if (order.getPaymentMethod() == OrderPaymentMethod.POINT) {
            order.requireDraft();
        }
        if (order.getStatus() == OrderStatus.DRAFT) {
            throw new PaymentRecoveryException(PaymentRecoveryException.Reason.PAYMENT_NOT_STARTED);
        }
        return recoverySnapshot(order, storedAttempt(order));
    }

    /** Internal callback/recovery re-read only; its target comes from persisted provider mapping, not a customer ID. */
    public RecoveryPayment inspectRecovery(long orderId, UUID attemptKey) {
        Order order = lockOrder(orderId);
        PaymentAttempt attempt = storedAttempt(order);
        if (!attempt.getAttemptKey().equals(attemptKey)) {
            throw new IllegalStateException("Stored recovery target changed");
        }
        return recoverySnapshot(order, attempt);
    }

    private RecoveryPayment recoverySnapshot(Order order, PaymentAttempt attempt) {
        return new RecoveryPayment(order.getId(), attempt.getAttemptKey(), attempt.getProviderAccount(),
            attempt.getProviderOrderId(), attempt.getTransactionKey(), attempt.getAmount(),
            snapshot(order, attempt, PaymentObservationResult.UNCHANGED));
    }

    /**
     * strict 422의 효과 0건을 adapter가 검증한 해당 생성 호출에만 사용한다.
     * 조회 0건·timeout은 이 메서드의 증거가 아니며 저장된 시도 대상과도 다시 비교한다.
     */
    public AppliedPayment rejectBeforeCreate(long orderId, UUID attemptKey, String providerAccount,
                                             String providerOrderId, long amount) {
        return observe(orderId, attemptKey,
            attempt -> attempt.observeRejectedBeforeCreate(providerAccount, providerOrderId, amount));
    }

    private AppliedPayment observe(long orderId, UUID attemptKey,
                                   Function<PaymentAttempt, PaymentObservationResult> observation) {
        Order order = lockOrder(orderId);
        order.validatePgPaymentState();
        Map<Long, Integer> quantities = quantities(order);
        // 삭제 후라도 이미 확보한 예약은 마무리한다. 일반 판매·신규 예약의 삭제 검사는 그대로다.
        Map<Long, Product> locked = lockProducts(quantities.keySet(), true);
        PaymentAttempt attempt = attempts.lockByOrderId(orderId)
            .orElseThrow(() -> new IllegalStateException("Prepared order has no payment attempt"));
        validateStoredAttempt(order, attempt);
        if (!attempt.getAttemptKey().equals(attemptKey)) {
            return snapshot(order, attempt, PaymentObservationResult.CONFLICT);
        }
        PaymentObservationResult result = observation.apply(attempt);
        if (result == PaymentObservationResult.CONFLICT) {
            // Fixed reason and internal identities only. Never log raw PG/callback content, card input or fingerprints.
            log.warn("payment_result_conflict source=RESULT_APPLICATION orderId={} attemptKey={} reason=OBSERVATION_MISMATCH",
                orderId, attempt.getAttemptKey());
        }
        if (result == PaymentObservationResult.APPLIED) {
            applyStockEffect(order, attempt, quantities, locked);
        }
        return snapshot(order, attempt, result);
    }

    private void applyStockEffect(Order order, PaymentAttempt attempt, Map<Long, Integer> quantities,
                                  Map<Long, Product> locked) {
        if (attempt.getStatus() == PaymentAttemptStatus.SUCCESS) {
            if (order.confirmPgPayment(ZonedDateTime.now(orderPaymentClock))) {
                // Build while the stored items are accessible; the listener sends only after the actual commit.
                events.publishEvent(OrderConfirmedEvent.from(order));
            }
        } else if (attempt.getStatus() == PaymentAttemptStatus.FAILED) {
            locked.forEach((id, product) -> product.validateReservedStockReturn(quantities.get(id)));
            if (order.failPgPayment()) {
                locked.forEach((id, product) -> product.returnReservedStock(quantities.get(id)));
                revisions.advance();
            }
        }
    }

    private void validateStoredAttempt(Order order, PaymentAttempt attempt) {
        OrderStatus expected = switch (attempt.getStatus()) {
            case SUCCESS -> OrderStatus.CONFIRMED;
            case FAILED -> OrderStatus.PAYMENT_FAILED;
            default -> OrderStatus.PAYMENT_PENDING;
        };
        if (attempt.getOrderId() != order.getId() || attempt.getAmount() != order.getTotalAmount()
            || attempt.getSendStartedAt() == null || order.getStatus() != expected) {
            throw new IllegalStateException("Stored order and payment attempt are inconsistent");
        }
    }

    private AppliedPayment snapshot(Order order, PaymentAttempt attempt, PaymentObservationResult result) {
        return new AppliedPayment(order.getStatus(), attempt.getStatus(), result);
    }

    private Order lockOrder(long orderId) {
        return orders.lockById(orderId)
            .orElseThrow(() -> new OrderException(OrderException.Reason.ORDER_NOT_FOUND));
    }

    private Map<Long, Integer> quantities(Order order) {
        return order.getItems().stream().collect(Collectors.toMap(OrderItem::getProductId, OrderItem::getQuantity));
    }

    private Map<Long, Product> lockProducts(Set<Long> productIds, boolean includeDeleted) {
        TreeSet<Long> brandIds = new TreeSet<>();
        for (long productId : new TreeSet<>(productIds)) {
            brandIds.add(products.findBrandId(productId).orElseThrow(this::productNotFound));
        }
        for (long brandId : brandIds) {
            Brand brand = brands.lockById(brandId).orElseThrow(this::productNotFound);
            if (!includeDeleted && brand.isDeleted()) {
                throw productNotFound();
            }
        }
        Map<Long, Product> locked = new TreeMap<>();
        for (long productId : new TreeSet<>(productIds)) {
            Product product = products.lockById(productId).orElseThrow(this::productNotFound);
            if (!includeDeleted && product.isDeleted()) {
                throw productNotFound();
            }
            locked.put(productId, product);
        }
        return locked;
    }

    private ProductQueryException productNotFound() {
        return new ProductQueryException(ProductQueryException.Reason.PRODUCT_NOT_FOUND);
    }

    public record PreparedPayment(long orderId, UUID attemptKey, String providerAccount,
                                  String providerOrderId, long amount) {
    }

    public record AppliedPayment(OrderStatus orderStatus, PaymentAttemptStatus paymentStatus,
                                 PaymentObservationResult observationResult) {
    }

    public record PaymentPreparation(PreparedPayment dispatch, AppliedPayment result) {
    }

    public record StoredPayment(String providerAccount, String providerOrderId, String transactionKey,
                                AppliedPayment result) {
        public boolean terminal() {
            return result.paymentStatus() == PaymentAttemptStatus.SUCCESS
                || result.paymentStatus() == PaymentAttemptStatus.FAILED;
        }
    }

    public record RecoveryPayment(long orderId, UUID attemptKey, String providerAccount, String providerOrderId,
                                  String transactionKey, long amount, AppliedPayment result) {
        public boolean terminal() {
            return result.paymentStatus() == PaymentAttemptStatus.SUCCESS
                || result.paymentStatus() == PaymentAttemptStatus.FAILED;
        }
    }
}
