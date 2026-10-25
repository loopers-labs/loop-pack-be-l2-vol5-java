package com.loopers.application.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentAttemptRepository;
import com.loopers.support.error.CoreException;
import com.loopers.support.error.ErrorType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.regex.Pattern;

// PG 결과 알림은 위조·중복·지연될 수 있으므로 본문의 상태를 결과로 쓰지 않는다.
// PG 주문 식별자로 시도를 찾은 뒤, 저장된 시도 기준으로 PG를 조회해 같은 결과 반영 경계를 사용한다.
@Slf4j
@RequiredArgsConstructor
@Component
public class PaymentCallbackFacade {
    private static final Pattern PROVIDER_ORDER_ID = Pattern.compile("^[A-Za-z0-9_-]{6,64}$");

    private final PaymentAttemptRepository paymentAttemptRepository;
    private final PaymentResultService paymentResultService;

    @Transactional(propagation = Propagation.NEVER)
    public PaymentCallbackResult handle(String providerOrderId) {
        if (providerOrderId == null || !PROVIDER_ORDER_ID.matcher(providerOrderId).matches()) {
            throw new CoreException(ErrorType.BAD_REQUEST, "PG 주문 식별자가 올바르지 않습니다.");
        }
        PaymentAttempt attempt = paymentAttemptRepository.findByProviderOrderId(providerOrderId)
            .orElseThrow(() -> new CoreException(ErrorType.NOT_FOUND, "결제 시도를 찾을 수 없습니다."));
        try {
            PaymentReflection reflection = paymentResultService.check(attempt.getId());
            if (reflection.outcome() == PaymentReflectionOutcome.LOOKUP_UNAVAILABLE) {
                log.warn("PG 조회 불가로 결과 알림을 처리하지 못했습니다. attemptId={}", attempt.getId());
                return PaymentCallbackResult.RETRY_LATER;
            }
            return PaymentCallbackResult.PROCESSED;
        } catch (PaymentReflectionRolledBackException exception) {
            return PaymentCallbackResult.RETRY_LATER;
        }
    }
}
