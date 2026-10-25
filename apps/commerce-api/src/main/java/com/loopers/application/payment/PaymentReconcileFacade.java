package com.loopers.application.payment;

import com.loopers.application.user.UserValidator;
import com.loopers.domain.order.OrderRepository;
import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

// 고객이 본인 주문의 기존 결제 결과를 PG에 다시 조회해 반영한다. 새 시도나 PG 생성 요청은 만들지 않는다.
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentReconcileFacade {
    private final UserValidator userValidator;
    private final OrderRepository orderRepository;
    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentResultService paymentResultService;

    @Transactional(propagation = Propagation.NEVER)
    public PaymentReflection reconcile(Long userId, Long orderId) {
        userValidator.validateExists(userId);
        orderRepository.findById(orderId)
            .filter(order -> order.getUserId().equals(userId))
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "주문을 찾을 수 없습니다."));
        PaymentAttempt attempt = paymentAttemptRepository.findByOrderId(orderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 시도를 찾을 수 없습니다."));
        try {
            // 이번 조회 결과와 마지막으로 저장된 결제 상태를 구분해 전달한다.
            return paymentResultService.check(attempt.getId());
        } catch (PaymentReflectionRolledBackException exception) {
            // TX B만 되돌아갔다. 저장된 상태를 다시 읽을 수 있으면 그 상태로 응답하고, 읽지 못하면 내부 오류로 끝난다.
            log.error("결제 결과 재확인의 반영이 되돌아가 저장된 상태로 응답합니다. attemptId={}, cause={}",
                attempt.getId(), exception.getCause().toString());
            return new PaymentReflection(PaymentReflectionOutcome.UNCHANGED, paymentResultService.read(attempt.getId()));
        }
    }
}
