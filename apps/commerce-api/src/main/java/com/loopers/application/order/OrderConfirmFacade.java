package com.loopers.application.order;

import com.loopers.application.payment.BeginConfirmationResult;
import com.loopers.application.payment.PaymentApplyResult;
import com.loopers.application.payment.PaymentConfirmCommand;
import com.loopers.application.payment.PaymentObservationApplier;
import com.loopers.application.payment.PaymentTransactionService;
import com.loopers.application.payment.PrepareConfirmationResult;
import com.loopers.domain.payment.CreateAdmission;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import com.loopers.support.persistence.ConstraintViolationClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@RequiredArgsConstructor
@Component
public class OrderConfirmFacade {

    private final PaymentTransactionService transactions;
    private final PaymentGateway gateway;
    private final PaymentObservationApplier applier;
    private final ConstraintViolationClassifier constraints;

    /**
     * 결제 시작(설계 4.3). 상위 TX 없이 아래 순서로 진행한다.
     * <ol>
     *   <li>같은 시도의 저장 결과가 있으면 그대로 돌려준다.</li>
     *   <li>재고를 확보하기 전에 생성 회로가 막혀 있으면 주문을 DRAFT 로 둔 채 503 으로 거절한다.</li>
     *   <li>TX A: 시도·재고 확보·결제 대기를 저장한다. 동시 요청이 먼저 저장했으면 그 결과를 돌려준다.</li>
     *   <li>TX 밖에서 PG 생성 POST 를 보낸다.</li>
     *   <li>TX B: 검증한 결과를 시도·주문·재고에 한 번만 반영한다.</li>
     * </ol>
     */
    public PaymentApplyResult confirm(Long userId, Long orderId, PaymentConfirmCommand command) {
        PrepareConfirmationResult prepared = transactions.prepareConfirmation(userId, orderId, command);
        if (prepared.status() == PrepareConfirmationResult.Status.STORED) {
            return stored(prepared.stored());
        }
        if (gateway.checkCreateAdmission() == CreateAdmission.BLOCKED) {
            return storedOr(userId, orderId, command, ErrorType.PG_CREATE_UNAVAILABLE);
        }
        BeginConfirmationResult begin;
        try {
            begin = transactions.beginConfirmation(userId, orderId, command);
        } catch (DataIntegrityViolationException failure) {
            if (!isPaymentAttemptRace(failure)) {
                throw failure;
            }
            return storedOr(userId, orderId, command, ErrorType.PAYMENT_ATTEMPT_CONFLICT);
        }
        if (begin.status() == BeginConfirmationResult.Status.STORED) {
            return stored(begin.stored());
        }
        return applier.apply(begin.token(), gateway.create(begin.request()));
    }

    /** 그 사이 다른 요청이 같은 시도를 저장했을 수 있으므로 다시 읽어 저장 결과를 우선한다. 없으면 주어진 오류로 거절한다. */
    private PaymentApplyResult storedOr(Long userId, Long orderId, PaymentConfirmCommand command, ErrorType otherwise) {
        PrepareConfirmationResult prepared = transactions.prepareConfirmation(userId, orderId, command);
        if (prepared.status() == PrepareConfirmationResult.Status.STORED) {
            return stored(prepared.stored());
        }
        throw new CoreException(otherwise);
    }

    /** 같은 주문 또는 같은 시도 키의 결제가 동시에 저장되며 난 unique 위반만 경쟁으로 본다. */
    private boolean isPaymentAttemptRace(DataIntegrityViolationException failure) {
        String constraint = constraints.namedUniqueViolation(failure).orElse("");
        return "uk_payment_order".equals(constraint) || "uk_payment_key".equals(constraint);
    }

    private static PaymentApplyResult stored(OrderInfo order) {
        boolean terminal = "CONFIRMED".equals(order.status()) || "PAYMENT_FAILED".equals(order.status());
        return new PaymentApplyResult(order, terminal ? PaymentApplyResult.Outcome.FINAL : PaymentApplyResult.Outcome.PENDING);
    }
}
