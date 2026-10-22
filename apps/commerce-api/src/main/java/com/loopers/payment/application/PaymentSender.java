package com.loopers.payment.application;

import com.loopers.payment.application.port.out.PaymentGatewayPort;
import com.loopers.payment.application.port.out.PaymentOutboxPort;
import com.loopers.payment.application.port.out.PaymentPort;
import com.loopers.payment.application.port.out.PgNotSentException;
import com.loopers.payment.application.port.out.PgPaymentRequest;
import com.loopers.payment.application.port.out.PgRejectedException;
import com.loopers.payment.application.port.out.PgTransaction;
import com.loopers.payment.application.port.out.PgUnknownResultException;
import com.loopers.payment.domain.Payment;
import com.loopers.payment.domain.PaymentRequestedEvent;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZonedDateTime;

/**
 * 발송 풀 스레드에서 PG 생성 요청을 한 번 보낸다 (plan.md 5-1, ADR-W5-01). {@link PaymentDispatcher}만 부른다.
 * <ol>
 *   <li>발송 권한: 장부 PENDING → CLAIMED와 결제 REQUESTED → SENDING을 한 트랜잭션으로. 진 실행은 아무것도 하지 않는다.</li>
 *   <li>트랜잭션 밖에서 PG 생성 1회 (재시도 없음).</li>
 *   <li>응답·예외를 결과 적용 경계(TX B)로 넘긴다.</li>
 * </ol>
 * {@code @Async}는 프록시로 동작하므로 이 빈 밖에서 불러야 비동기다. 풀 이름을 빼면 대기열이 무한인 기본 풀로 가서 격벽이 사라진다.
 */
@Slf4j
@Component
public class PaymentSender {

    public static final String EXECUTOR = "paymentDispatchExecutor";

    private final PaymentPort paymentPort;
    private final PaymentOutboxPort paymentOutboxPort;
    private final PaymentGatewayPort paymentGatewayPort;
    private final PaymentResultApplier paymentResultApplier;
    private final TransactionTemplate newTransaction;

    public PaymentSender(
        PaymentPort paymentPort,
        PaymentOutboxPort paymentOutboxPort,
        PaymentGatewayPort paymentGatewayPort,
        PaymentResultApplier paymentResultApplier,
        PlatformTransactionManager transactionManager
    ) {
        this.paymentPort = paymentPort;
        this.paymentOutboxPort = paymentOutboxPort;
        this.paymentGatewayPort = paymentGatewayPort;
        this.paymentResultApplier = paymentResultApplier;
        this.newTransaction = new TransactionTemplate(transactionManager);
        this.newTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 풀이 가득 차면 실행되지 않고 호출한 스레드에 TaskRejectedException이 바로 던져진다.
     */
    @Async(EXECUTOR)
    public void send(PaymentRequestedEvent event) {
        Long paymentId = event.paymentId();
        try {
            PgPaymentRequest request = claim(paymentId, event);
            if (request == null) {
                return;
            }
            sendClaimed(paymentId, request);
        } catch (RuntimeException e) {
            // 발송 권한을 얻은 뒤 반영에 실패하면 결제는 SENDING으로 남고, 정리 스케줄러가 UNKNOWN으로 넘긴다
            log.error("결제 발송 처리 중 오류. paymentId={}", paymentId, e);
        }
    }

    /**
     * 발송 권한을 얻으면 PG 요청을, 다른 실행(정리 스케줄러)이 먼저 가졌으면 null을 돌려준다.
     */
    private PgPaymentRequest claim(Long paymentId, PaymentRequestedEvent event) {
        return newTransaction.execute(status -> {
            ZonedDateTime now = ZonedDateTime.now();
            if (!paymentOutboxPort.claim(paymentId, now)) {
                return null;
            }
            Payment payment = paymentPort.findByIdForUpdate(paymentId).orElseThrow();
            payment.markSending(now);
            paymentPort.save(payment);
            return new PgPaymentRequest(payment.getProviderOrderId(), event.cardType(), event.cardNo(), payment.getAmount().amount());
        });
    }

    private void sendClaimed(Long paymentId, PgPaymentRequest request) {
        PgTransaction created;
        try {
            created = paymentGatewayPort.request(request);
        } catch (PgRejectedException e) {
            paymentResultApplier.recordRejected(paymentId, e.getMessage());
            return;
        } catch (PgNotSentException e) {
            paymentResultApplier.closeNotSentAfterClaim(paymentId);
            return;
        } catch (PgUnknownResultException e) {
            log.warn("PG 생성 결과를 모릅니다 — 조회로만 확인합니다. paymentId={}, reason={}", paymentId, e.getMessage());
            paymentResultApplier.recordUnknown(paymentId);
            return;
        } catch (RuntimeException e) {
            // 분류하지 못한 예외는 보냈을 수 있다고 본다 — 실패로 닫지 않는다
            log.warn("PG 생성 중 예상하지 못한 오류 — UNKNOWN으로 둡니다. paymentId={}", paymentId, e);
            paymentResultApplier.recordUnknown(paymentId);
            return;
        }
        paymentResultApplier.recordCreated(paymentId, created);
    }
}
