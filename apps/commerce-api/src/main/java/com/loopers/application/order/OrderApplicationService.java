package com.loopers.application.order;

import com.loopers.application.order.port.OrderRepository;
import com.loopers.application.payment.PaymentGatewayRegistry;
import com.loopers.application.payment.PaymentLookupNotCalledException;
import com.loopers.application.payment.PaymentLookupPolicy;
import com.loopers.application.payment.port.PaymentAnomalyRepository;
import com.loopers.application.payment.port.PaymentAnomalyRepository.Kind;
import com.loopers.application.payment.port.PaymentCreateCommand;
import com.loopers.application.payment.port.PaymentCreateResult;
import com.loopers.application.payment.port.PaymentGateway;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository;
import com.loopers.application.payment.port.PaymentLookupScheduleRepository.Schedule;
import com.loopers.application.payment.port.PaymentRecordRepository;
import com.loopers.application.payment.port.PaymentTransactionsLookup;
import com.loopers.application.product.ProductNotFoundException;
import com.loopers.application.product.port.ProductRepository;
import com.loopers.domain.common.InvalidValueException;
import com.loopers.domain.common.RuleViolationException;
import com.loopers.domain.order.Order;
import com.loopers.domain.order.OrderItem;
import com.loopers.domain.order.Quantity;
import com.loopers.domain.payment.PaymentRecord;
import com.loopers.domain.product.Product;
import com.loopers.domain.product.ProductId;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

// confirm은 PG 호출을 트랜잭션·잠금 밖에 두기 위해 경계를 둘로 나눈다(설계 23절). 같은 객체의 @Transactional 메서드
// 호출은 프록시를 거치지 않아 경계가 생기지 않으므로, 트랜잭션 A와 결과 반영은 TransactionTemplate으로 연다.
@Slf4j
@Service
public class OrderApplicationService {
    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final PaymentRecordRepository paymentRecordRepository;
    private final PaymentGatewayRegistry paymentGatewayRegistry;
    private final TransactionTemplate transactionTemplate;
    private final PaymentAnomalyRepository paymentAnomalyRepository;
    private final Clock clock;
    private final PaymentLookupScheduleRepository paymentLookupScheduleRepository;
    private final PaymentLookupPolicy paymentLookupPolicy;

    public OrderApplicationService(OrderRepository orderRepository, ProductRepository productRepository,
        PaymentRecordRepository paymentRecordRepository, PaymentGatewayRegistry paymentGatewayRegistry,
        TransactionTemplate transactionTemplate, PaymentAnomalyRepository paymentAnomalyRepository, Clock clock,
        PaymentLookupScheduleRepository paymentLookupScheduleRepository, PaymentLookupPolicy paymentLookupPolicy) {
        this.orderRepository = orderRepository;
        this.productRepository = productRepository;
        this.paymentRecordRepository = paymentRecordRepository;
        this.paymentGatewayRegistry = paymentGatewayRegistry;
        this.transactionTemplate = transactionTemplate;
        this.paymentAnomalyRepository = paymentAnomalyRepository;
        this.clock = clock;
        this.paymentLookupScheduleRepository = paymentLookupScheduleRepository;
        this.paymentLookupPolicy = paymentLookupPolicy;
    }

    public record ItemRequest(long productId, int quantity) { }

    private enum PaymentSource {
        CREATE, CALLBACK, MANUAL, AUTOMATIC;

        boolean canResolveReview() {
            return this == MANUAL;
        }
    }

    private record PaymentStart(OrderResult current, PaymentRecord payment) {
        boolean newlyStarted() {
            return current == null;
        }
    }

    @Transactional
    public OrderResult create(long userId, List<ItemRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 100) {
            throw new InvalidValueException("주문 품목은 1~100개여야 합니다.");
        }
        // 상품 행은 ID 오름차순으로 잠근다. 주문 확정과 같은 순서를 유지해야 교착 가능성이 줄어든다.
        List<OrderItem> items = requested.stream()
            .sorted(Comparator.comparingLong(ItemRequest::productId))
            .map(request -> {
                Product product = productRepository.findByIdForUpdate(new ProductId(request.productId()))
                    .filter(found -> !found.isDeleted())
                    .orElseThrow(ProductNotFoundException::new);
                return new OrderItem(product.getId(), new Quantity(request.quantity()), product.getPrice());
            })
            .toList();
        return OrderResult.from(orderRepository.save(Order.create(userId, items)), Optional.empty());
    }

    public OrderResult confirm(long userId, long orderId, ConfirmCommand command) {
        PaymentStart start = transactionTemplate.execute(status -> startPayment(userId, orderId, command.attemptKey()));
        if (!start.newlyStarted()) {
            // 이미 발송 시작된 결제는 결과를 모르더라도 create를 다시 보내지 않는다. 확인은 조회로만 한다(설계 23절).
            return start.current();
        }
        PaymentRecord payment = start.payment();
        PaymentReport report = createPayment(payment, command);
        try {
            return applyPaymentResult(orderId, report);
        } catch (RuntimeException e) {
            // 외부 결제 사실은 rollback할 수 없으므로 저장된 주문을 새로 읽어 확인 경로로 돌려준다.
            log.error("PG 호출 후 결과 저장에 실패했습니다. orderId={}, providerStatus={}", orderId, report.status());
            return transactionTemplate.execute(status -> withPayment(
                orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new)));
        }
    }

    private PaymentReport createPayment(PaymentRecord payment, ConfirmCommand command) {
        List<PaymentGateway> gateways = paymentGatewayRegistry.inPriorityOrder();
        for (int index = 0; index < gateways.size(); index++) {
            PaymentGateway gateway = gateways.get(index);
            PaymentCreateResult created = gateway.create(new PaymentCreateCommand(
                payment.getProviderOrderId(), command.cardType(), command.cardNo(), payment.getAmount()));
            log.info("PG 결제 생성 시도 결과. orderId={}, providerId={}, result={}",
                payment.getOrderId(), gateway.providerId(), created.getClass().getSimpleName());
            if (!(created instanceof PaymentCreateResult.NotSent)) {
                return PaymentReport.from(created);
            }
            if (index + 1 == gateways.size()) {
                return new PaymentReport(PaymentRecord.ProviderStatus.ALL_NOT_SENT, null);
            }
            String nextProviderId = gateways.get(index + 1).providerId();
            String currentProviderId = gateway.providerId();
            Boolean switched = transactionTemplate.execute(status -> {
                Order order = orderRepository.findByIdForUpdate(payment.getOrderId()).orElseThrow(OrderNotFoundException::new);
                PaymentRecord current = paymentRecordRepository.findByOrderId(order.getId()).orElseThrow();
                if (order.getStatus() != Order.Status.PAYMENT_PENDING
                    || current.getResult() != PaymentRecord.Result.PENDING
                    || !currentProviderId.equals(current.getProviderId())) {
                    return false;
                }
                // 다음 PG를 부르기 전에 저장해야 중단 후에도 조회 대상이 발송 가능성이 있는 PG를 가리킨다.
                current.switchProvider(nextProviderId);
                paymentRecordRepository.save(current);
                return true;
            });
            if (!Boolean.TRUE.equals(switched)) {
                return new PaymentReport(PaymentRecord.ProviderStatus.UNKNOWN, null);
            }
        }
        throw new IllegalStateException("PG 우선순위 목록이 비어 있습니다.");
    }

    // PG가 보고한 결과 하나를 반영하는 유일한 경계. create 응답·조회·callback 모두 여기로 모은다(설계 23절).
    // 잠금 순서는 confirm과 같은 주문 → 상품 ID 오름차순이다. 상품은 실패로 재고를 돌려줄 때만 잠근다.
    public OrderResult applyPaymentResult(long orderId, PaymentReport report) {
        return transactionTemplate.execute(status -> {
            Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow(OrderNotFoundException::new);
            return applyLocked(order, paymentRecordRepository.findByOrderId(orderId), report, PaymentSource.CREATE);
        });
    }

    public void receivePaymentCallback(String providerOrderId, String callbackKey) {
        Optional<PaymentRecord> found = paymentRecordRepository.findByProviderOrderId(providerOrderId);
        if (found.isEmpty()) {
            log.warn("알 수 없는 주문의 PG callback입니다.");
            return;
        }
        PaymentRecord payment = found.get();
        if (payment.getProviderStatus() == PaymentRecord.ProviderStatus.NEEDS_REVIEW) {
            return;
        }
        verifyPayment(payment, PaymentSource.CALLBACK, callbackKey);
    }

    public OrderResult reconcileMyPayment(long userId, long orderId) {
        orderRepository.findById(orderId)
            .filter(order -> order.getUserId() == userId)
            .orElseThrow(OrderNotFoundException::new);
        return reconcilePayment(orderId);
    }

    public OrderResult reconcilePayment(long orderId) {
        orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new);
        PaymentRecord payment = paymentRecordRepository.findByOrderId(orderId)
            .filter(found -> found.getMethod() == PaymentRecord.Method.PG)
            .orElseThrow(() -> new RuleViolationException("PG 결제가 아닌 주문은 조회 확정할 수 없습니다."));
        return verifyPayment(payment, PaymentSource.MANUAL, null);
    }

    public void reconcileDuePayments() {
        List<Long> dueOrders = paymentLookupScheduleRepository.findDue(clock.instant(), paymentLookupPolicy.batchSize());
        for (long orderId : dueOrders) {
            try {
                Optional<PaymentRecord> payment = paymentRecordRepository.findByOrderId(orderId);
                if (payment.isPresent() && payment.get().getProviderStatus() != PaymentRecord.ProviderStatus.NEEDS_REVIEW) {
                    verifyPayment(payment.get(), PaymentSource.AUTOMATIC, null);
                }
            } catch (RuntimeException e) {
                log.error("주문 자동 재조회 처리 실패. orderId={}, causeType={}", orderId, e.getClass().getSimpleName());
            }
        }
    }

    private OrderResult verifyPayment(PaymentRecord requested, PaymentSource source, String callbackKey) {
        // callback 본문은 누구나 보낼 수 있는 신호다. 저장된 값으로 잠금·트랜잭션 밖에서 PG를 조회한다.
        PaymentTransactionsLookup lookup = paymentGatewayRegistry.get(requested.getProviderId())
            .verifyOrder(requested.getProviderOrderId(), requested.getTransactionKey());
        if (lookup instanceof PaymentTransactionsLookup.NotCalled) {
            log.info("조회 회로 정책으로 PG를 호출하지 않았습니다. orderId={}, providerId={}, source={}",
                requested.getOrderId(), requested.getProviderId(), source);
            if (source == PaymentSource.MANUAL) {
                throw new PaymentLookupNotCalledException();
            }
        }
        return transactionTemplate.execute(status -> {
            Order order = orderRepository.findByIdForUpdate(requested.getOrderId()).orElseThrow(OrderNotFoundException::new);
            PaymentRecord payment = paymentRecordRepository.findByOrderId(order.getId()).orElseThrow();
            if (!payment.getProviderId().equals(requested.getProviderId())
                || (source != PaymentSource.MANUAL && payment.getProviderStatus() == PaymentRecord.ProviderStatus.NEEDS_REVIEW)) {
                return OrderResult.from(order, Optional.of(payment));
            }
            OrderResult result = applyLookupLocked(order, payment, lookup, source, callbackKey);
            if (source == PaymentSource.AUTOMATIC) {
                advanceLookupSchedule(order, payment, lookup);
            }
            return result;
        });
    }

    private OrderResult applyLookupLocked(Order order, PaymentRecord payment, PaymentTransactionsLookup lookup,
        PaymentSource source, String callbackKey) {
        PaymentLookupAssessment assessment = PaymentLookupAssessment.assess(payment, lookup);
        if (source == PaymentSource.CALLBACK) {
            recordCallbackKeyMismatch(order.getId(), assessment.callbackComparisonKey(), callbackKey);
        }
        return switch (assessment) {
            case PaymentLookupAssessment.NotCalled notCalled -> OrderResult.from(order, Optional.of(payment));
            case PaymentLookupAssessment.Uncertain uncertain -> {
                recordTransientAnomaly(order, uncertain.kind(), uncertain.summary());
                yield OrderResult.from(order, Optional.of(payment));
            }
            case PaymentLookupAssessment.Review review -> requireReview(order, payment, review.kind(), review.summary());
            case PaymentLookupAssessment.Verified verified -> applyLocked(order, Optional.of(payment), verified.report(), source);
        };
    }

    private void advanceLookupSchedule(Order order, PaymentRecord payment, PaymentTransactionsLookup lookup) {
        if (order.getStatus() != Order.Status.PAYMENT_PENDING
            || payment.getProviderStatus() == PaymentRecord.ProviderStatus.NEEDS_REVIEW) {
            paymentLookupScheduleRepository.delete(order.getId());
            return;
        }
        Optional<Schedule> found = paymentLookupScheduleRepository.findByOrderId(order.getId());
        if (found.isEmpty()) {
            return;
        }
        Schedule schedule = found.get();
        boolean contacted = !(lookup instanceof PaymentTransactionsLookup.NotCalled);
        int attempts = schedule.actualAttempts() + (contacted ? 1 : 0);
        if (contacted && attempts >= paymentLookupPolicy.maxAttempts()) {
            // 조회 한도는 효과 없음의 증거가 아니다. 실패 확정 대신 수동 확인 대상으로 남긴다.
            requireReview(order, payment, Kind.LOOKUP_LIMIT_EXHAUSTED, "actualAttempts=" + attempts);
            return;
        }
        Instant now = clock.instant();
        paymentLookupScheduleRepository.save(new Schedule(order.getId(), paymentLookupPolicy.nextAt(now, attempts),
            attempts, schedule.createdAt(), now));
    }

    private void recordTransientAnomaly(Order order, Kind kind, String summary) {
        if (order.getStatus() == Order.Status.PAYMENT_PENDING) {
            recordAnomaly(order.getId(), kind, summary);
        } else {
            // 종결 후의 불확실한 조회로 해결된 이상을 다시 열거나 영구히 미해결인 행을 만들지 않는다.
            log.info("결제 대기가 아닌 주문의 미확정 PG 조회 결과입니다. orderId={}, status={}, kind={}",
                order.getId(), order.getStatus(), kind);
        }
    }

    private void recordCallbackKeyMismatch(long orderId, String comparisonKey, String callbackKey) {
        if (comparisonKey != null && !comparisonKey.equals(callbackKey)) {
            recordAnomaly(orderId, Kind.CALLBACK_KEY_MISMATCH, "comparisonKeyMatches=false");
        }
    }

    private OrderResult requireReview(Order order, PaymentRecord payment, Kind kind, String summary) {
        recordAnomaly(order.getId(), kind, summary);
        payment.requireReview();
        paymentLookupScheduleRepository.delete(order.getId());
        return OrderResult.from(order, Optional.of(paymentRecordRepository.save(payment)));
    }

    private OrderResult applyLocked(Order order, Optional<PaymentRecord> found, PaymentReport report, PaymentSource source) {
        if (order.getStatus() == Order.Status.DRAFT) {
            recordAnomaly(order.getId(), Kind.RESULT_BEFORE_PAYMENT, "status=" + report.status());
            log.warn("결제를 시작하지 않은 주문에 결제 결과가 들어왔습니다. orderId={}, providerStatus={}",
                order.getId(), report.status());
            return OrderResult.from(order, found);
        }
        if (found.isEmpty() || found.get().getMethod() != PaymentRecord.Method.PG) {
            log.warn("PG 결제 기록이 없는 주문에 결제 결과가 들어왔습니다. orderId={}, providerStatus={}", order.getId(), report.status());
            return OrderResult.from(order, found);
        }
        PaymentRecord payment = found.get();
        // 확인 필요는 수동 reconcile의 깨끗한 확정 결과만 해제한다. 미확정 보고는 키와 상태를 보존한다.
        if (payment.getProviderStatus() == PaymentRecord.ProviderStatus.NEEDS_REVIEW
            && (!source.canResolveReview() || report.outcome() == Order.PaymentOutcome.UNKNOWN)) {
            return OrderResult.from(order, found);
        }
        Order.PaymentTransition transition = order.applyPaymentOutcome(report.outcome());
        switch (transition) {
            case CHANGED -> {
                if (order.getStatus() == Order.Status.PAYMENT_FAILED) {
                    returnReservedStock(order);
                }
                orderRepository.save(order);
                payment.applyProviderReport(report.status(), report.transactionKey());
                payment = paymentRecordRepository.save(payment);
                paymentAnomalyRepository.resolveTransient(order.getId(), clock.instant());
                paymentLookupScheduleRepository.delete(order.getId());
            }
            case UNCHANGED -> {
                if (order.getStatus() == Order.Status.PAYMENT_PENDING) {
                    payment.applyProviderReport(report.status(), report.transactionKey());
                    payment = paymentRecordRepository.save(payment);
                }
            }
            case CONFLICT -> {
                recordAnomaly(order.getId(), Kind.CONFLICTING_RESULT, "status=" + report.status());
                log.warn("종결된 주문에 반대 결제 결과가 들어와 반영하지 않았습니다. orderId={}, status={}, providerStatus={}",
                    order.getId(), order.getStatus(), report.status());
            }
            case ANOMALY -> throw new IllegalStateException("DRAFT 결과는 앞에서 처리해야 합니다.");
        }
        return OrderResult.from(order, Optional.of(payment));
    }

    private void recordAnomaly(long orderId, Kind kind, String summary) {
        paymentAnomalyRepository.record(orderId, kind, summary, clock.instant());
    }

    // 트랜잭션 A. 주문 잠금 → 본인·상태·시도 키 확인 → 상품 ID 오름차순 잠금·재고 확보 → 결제 기록(발송 시작) → 결제 대기.
    // 재고 부족·삭제 상품이면 예외로 전체 rollback되어 PG는 한 번도 부르지 않는다. 1회 발송은 주문 상태 잠금이 지킨다.
    private PaymentStart startPayment(long userId, long orderId, String attemptKey) {
        Order order = orderRepository.findByIdForUpdate(orderId)
            .filter(found -> found.getUserId() == userId)
            .orElseThrow(OrderNotFoundException::new);
        if (order.getStatus() != Order.Status.DRAFT) {
            PaymentRecord payment = paymentRecordRepository.findByOrderId(orderId)
                .filter(found -> attemptKey.equals(found.getAttemptKey()))
                .orElseThrow(() -> new RuleViolationException("이미 다른 결제가 진행되었거나 끝난 주문입니다."));
            return new PaymentStart(OrderResult.from(order, Optional.of(payment)), payment);
        }
        if (paymentRecordRepository.findByAttemptKey(attemptKey).isPresent()) {
            throw new RuleViolationException("다른 주문에 사용한 결제 시도 키입니다.");
        }
        reserveStock(order);
        order.startPayment();
        orderRepository.save(order);
        String providerId = paymentGatewayRegistry.inPriorityOrder().get(0).providerId();
        PaymentRecord payment;
        try {
            payment = paymentRecordRepository.save(PaymentRecord.pgStarted(orderId, order.getTotal(), attemptKey, providerId));
        } catch (DataIntegrityViolationException e) {
            // 같은 시도 키를 다른 주문이 동시에 쓴 경쟁. 사전 확인을 통과했어도 유일 제약이 막는다.
            throw new RuleViolationException("다른 주문에 사용한 결제 시도 키입니다.", e);
        }
        Instant now = clock.instant();
        paymentLookupScheduleRepository.save(new Schedule(orderId, paymentLookupPolicy.nextAt(now, 0), 0, now, now));
        return new PaymentStart(null, payment);
    }

    private void reserveStock(Order order) {
        for (OrderItem item : itemsByProductId(order)) {
            Product product = productRepository.findByIdForUpdate(item.productId())
                .filter(found -> !found.isDeleted())
                .orElseThrow(ProductNotFoundException::new);
            product.decreaseStock(item.quantity().value());
            productRepository.save(product);
        }
    }

    // 결제 대기 중 삭제된 상품도 반환한다. 삭제 상태는 유지되어 다시 노출되지 않는다.
    private void returnReservedStock(Order order) {
        for (OrderItem item : itemsByProductId(order)) {
            Product product = productRepository.findByIdForUpdate(item.productId()).orElseThrow(ProductNotFoundException::new);
            product.returnStock(item.quantity().value());
            productRepository.save(product);
        }
    }

    private List<OrderItem> itemsByProductId(Order order) {
        return order.getItems().stream()
            .sorted(Comparator.comparingLong(orderItem -> orderItem.productId().value()))
            .toList();
    }

    @Transactional(readOnly = true)
    public OrderResult getMyOrder(long userId, long orderId) {
        Order order = orderRepository.findById(orderId)
            .filter(found -> found.getUserId() == userId)
            .orElseThrow(OrderNotFoundException::new);
        return withPayment(order);
    }

    @Transactional(readOnly = true)
    public OrderResult getAdminOrder(long orderId) {
        return withPayment(orderRepository.findById(orderId).orElseThrow(OrderNotFoundException::new));
    }

    @Transactional(readOnly = true)
    public List<OrderResult> listMyOrders(long userId, int page, int size) {
        return withPayments(orderRepository.findPage(userId, page, size));
    }

    @Transactional(readOnly = true)
    public List<OrderResult> listAdminOrders(Long userId, int page, int size) {
        return withPayments(orderRepository.findPage(userId, page, size));
    }

    private OrderResult withPayment(Order order) {
        return OrderResult.from(order, paymentRecordRepository.findByOrderId(order.getId()));
    }

    private List<OrderResult> withPayments(List<Order> orders) {
        List<Long> orderIds = orders.stream().map(Order::getId).toList();
        Map<Long, PaymentRecord> paymentsByOrderId = paymentRecordRepository.findByOrderIds(orderIds).stream()
            .collect(Collectors.toMap(PaymentRecord::getOrderId, Function.identity()));
        return orders.stream()
            .map(order -> OrderResult.from(order, Optional.ofNullable(paymentsByOrderId.get(order.getId()))))
            .toList();
    }
}
