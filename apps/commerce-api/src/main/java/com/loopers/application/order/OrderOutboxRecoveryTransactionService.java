package com.loopers.application.order;

import com.loopers.application.order.OrderOutboxOperations.RecoveryItem;
import com.loopers.application.order.OrderOutboxOperations.RecoveryOutcome;
import com.loopers.config.OrderOutboxClockConfig;
import com.loopers.domain.order.OrderConfirmed;
import com.loopers.domain.order.OrderConfirmedMessageEncoder;
import com.loopers.domain.order.OrderConfirmedOutboxModel;
import com.loopers.domain.order.OrderConfirmedOutboxModel.Transition;
import com.loopers.domain.order.OrderConfirmedOutboxRepository;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.order.OrderOutboxMessageVerifier;
import com.loopers.domain.order.OrderOutboxStatus;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.order.OrderStatus;
import com.loopers.domain.order.PaymentMethod;
import com.loopers.domain.payment.PaymentModel;
import com.loopers.domain.payment.PaymentRepository;
import com.loopers.domain.payment.PaymentStatus;
import com.loopers.domain.payment.PgOrderConsistency;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.ZonedDateTime;
import java.util.Optional;

/**
 * 운영 진입점의 TX 경계(설계 W7.3-2·W7.3-6·W7.3-7). 주문별 복구 recoverOne 과 BLOCKED 재개 resumeBlocked 를 제공한다.
 * 외부 전송·PG 호출·재확정·예약/재고/포인트 변경이 없다. unique 오류 뒤 새 TX 재확인은 하지 않는다.
 */
@Component
public class OrderOutboxRecoveryTransactionService {

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final OrderConfirmedOutboxRepository repository;
    private final OrderConfirmedMessageEncoder encoder;
    private final OrderOutboxMessageVerifier verifier;
    private final Clock clock;
    private final String providerAccount;

    public OrderOutboxRecoveryTransactionService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                                                 OrderConfirmedOutboxRepository repository, OrderConfirmedMessageEncoder encoder,
                                                 OrderOutboxMessageVerifier verifier,
                                                 @Qualifier(OrderOutboxClockConfig.OUTBOX_RECORD_CLOCK) Clock clock,
                                                 @Value("${commerce.pg.account-id}") String providerAccount) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.repository = repository;
        this.encoder = encoder;
        this.verifier = verifier;
        this.clock = clock;
        this.providerAccount = providerAccount;
    }

    /**
     * 저장된 PG 확정 주문 하나의 확정 사실을 Outbox 에서 보장한다. 첫 업무 SQL 은 Order PK 잠금, Payment 는 일반 조회, Outbox 는 그 뒤 잠금 조회다.
     * 기존 행이 있으면 전달 상태 정합성과 같은 사실만 확인하고 아무것도 바꾸지 않는다(EXISTS). 없으면 저장 주문의 사건을 PENDING 으로 넣는다(RESTORED).
     * 원본 부족·저장 상태 불일치·사실 충돌은 오류이며 현재 상품·새 시각·UUID 로 보정하지 않는다. 최근 verificationStatus 만으로 SUCCESS 를 취소하지 않는다.
     */
    @Transactional
    public RecoveryItem recoverOne(long orderId) {
        OrderModel order = orderRepository.findForUpdate(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.ORDER_NOT_FOUND));
        PaymentModel payment = paymentRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.INTERNAL_ERROR, "복구 대상 주문에 결제 시도가 없습니다. [orderId = " + orderId + "]"));
        PgOrderConsistency.requireConsistent(order, payment, providerAccount);
        // 복구 추가 전제. 공통 정합성 검사에 넣지 않아 기존 PG 흐름의 정책을 바꾸지 않는다.
        if (order.getPaymentMethod() != PaymentMethod.PG || order.getStatus() != OrderStatus.CONFIRMED
            || payment.getStatus() != PaymentStatus.SUCCESS
            || payment.getTransactionKey() == null || payment.getTransactionKey().isBlank()) {
            throw new CoreException(ErrorType.INTERNAL_ERROR, "PG 확정·SUCCESS·거래 키가 있는 주문만 복구할 수 있습니다. [orderId = " + orderId + "]");
        }
        OrderConfirmed expected = OrderConfirmed.from(order);

        Optional<OrderConfirmedOutboxModel> existing = repository.findForUpdateByEventId(expected.eventId());
        if (existing.isPresent()) {
            OrderConfirmedOutboxModel outbox = existing.get();
            outbox.requireDeliveryState();
            verifier.requireSameFact(expected, outbox.storedMessage());
            return new RecoveryItem(orderId, expected.eventId(), RecoveryOutcome.EXISTS, outbox.getStatus());
        }
        repository.insertAndFlush(OrderConfirmedOutboxModel.pending(encoder.encode(expected), OrderOutboxRelay.now(clock)));
        return new RecoveryItem(orderId, expected.eventId(), RecoveryOutcome.RESTORED, OrderOutboxStatus.PENDING);
    }

    /** 행을 잠그고 BLOCKED 면 재개한다. PENDING·PUBLISHED 는 변경 없이 해당 결과다. 없는 eventId 는 오류다. */
    @Transactional
    public Transition resumeBlocked(String eventId, ZonedDateTime now) {
        OrderConfirmedOutboxModel outbox = repository.findForUpdateByEventId(eventId)
            .orElseThrow(() -> new IllegalArgumentException("Outbox 행이 없는 eventId 입니다."));
        Transition transition = outbox.resume(now);
        if (transition == Transition.APPLIED) {
            repository.saveAndFlush(outbox);
        }
        return transition;
    }
}
