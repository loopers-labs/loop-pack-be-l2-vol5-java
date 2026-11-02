package com.loopers.purchase.application;

import com.loopers.purchase.application.port.in.PurchaseActivityUseCase;
import com.loopers.purchase.application.port.out.PurchaseActivityPort;
import com.loopers.purchase.domain.ConflictingOrderConfirmationException;
import com.loopers.purchase.domain.ConsumedOrderConfirmation;
import com.loopers.purchase.domain.ConsumptionVerdict;
import com.loopers.purchase.domain.OrderConfirmedMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.SQLException;
import java.util.Optional;

/**
 * 멱등 consumer (W6 D-6, ADR-W6-05).
 * <ol>
 *   <li>빠른 길 — 완료 기록을 eventId 또는 주문으로 찾으면 판정한다. 평소 재전달은 여기서 예외 없이 끝난다.</li>
 *   <li>저장 — 완료 기록 + 품목 N개를 한 트랜잭션으로. 중간 품목이 실패하면 전부 rollback.</li>
 *   <li>유일 제약 위반(동시 처리)만 "이미 있을 수 있음"으로 보고, 실패한 TX를 끝낸 뒤 새 TX에서 다시 찾아 판정한다.
 *       다른 DB 오류는 중복으로 바꾸지 않고 그대로 던진다.</li>
 * </ol>
 */
@Slf4j
@Service
public class PurchaseActivityService implements PurchaseActivityUseCase {

    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private final PurchaseActivityPort purchaseActivityPort;
    private final TransactionTemplate transaction;

    public PurchaseActivityService(PurchaseActivityPort purchaseActivityPort, PlatformTransactionManager transactionManager) {
        this.purchaseActivityPort = purchaseActivityPort;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    @Override
    public Result consume(OrderConfirmedMessage message, String payload) {
        Optional<ConsumedOrderConfirmation> existing = findExisting(message);
        if (existing.isPresent()) {
            return judge(existing.get(), message, payload);
        }
        try {
            transaction.executeWithoutResult(status -> purchaseActivityPort.saveAll(message, payload));
            log.info("구매 활동 저장. eventId={}, orderId={}, items={}", message.eventId(), message.orderId(), message.normalizedItems().size());
            return Result.SAVED;
        } catch (DataIntegrityViolationException e) {
            if (!isDuplicateKey(e)) {
                throw e;
            }
            ConsumedOrderConfirmation winner = findExisting(message)
                .orElseThrow(() -> e); // 유일 제약 위반인데 기록이 없다면 다른 문제다
            return judge(winner, message, payload);
        }
    }

    private Optional<ConsumedOrderConfirmation> findExisting(OrderConfirmedMessage message) {
        return transaction.execute(status -> purchaseActivityPort.findByEventIdOrOrderId(message.eventId(), message.orderId()));
    }

    private Result judge(ConsumedOrderConfirmation existing, OrderConfirmedMessage message, String payload) {
        ConsumptionVerdict verdict = existing.judge(message);
        if (verdict == ConsumptionVerdict.DUPLICATE) {
            log.info("같은 사실의 재전달 — 변경 없이 완료(no-op). eventId={}, orderId={}", message.eventId(), message.orderId());
            return Result.DUPLICATE;
        }
        throw new ConflictingOrderConfirmationException(verdict, existing.getPayload(), payload);
    }

    private static boolean isDuplicateKey(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == MYSQL_DUPLICATE_ENTRY) {
                return true;
            }
        }
        return false;
    }
}
