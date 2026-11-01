package com.loopers.application.purchase;

import com.loopers.application.purchase.dto.PurchaseSaveResult;
import com.loopers.application.purchase.dto.SavePurchaseActivityCommand;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.PurchaseActivityService;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 주문 확정 사실을 구매 활동으로 기록하는 유스케이스. 완료 기록과 모든 품목을 한 트랜잭션에 저장한다.
 *
 * <p>유일 제약 경쟁에서 진 쪽은 트랜잭션이 rollback 된 뒤 그 밖에서 저장된 사실을 다시 읽어 같은지 확인한다. 실패한
 * 트랜잭션 안에서는 예외를 삼키고 계속할 수 없어 {@link TransactionTemplate} 으로 경계를 직접 잡는다.
 */
@Component
public class PurchaseActivityFacade {

    private final PurchaseActivityService purchaseActivityService;
    private final TransactionTemplate transaction;

    public PurchaseActivityFacade(PurchaseActivityService purchaseActivityService, PlatformTransactionManager transactionManager) {
        this.purchaseActivityService = purchaseActivityService;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    public PurchaseSaveResult save(SavePurchaseActivityCommand command) {
        OrderConfirmation confirmation = command.toConfirmation();
        try {
            boolean saved = Boolean.TRUE.equals(transaction.execute(status -> purchaseActivityService.save(confirmation)));
            return saved ? PurchaseSaveResult.SAVED : PurchaseSaveResult.DUPLICATE;
        } catch (DataIntegrityViolationException e) {
            // 중복 때문이 아닌 제약 오류를 중복으로 바꾸지 않도록, 같은 사실이 실제로 저장돼 있을 때만 정상으로 본다.
            if (purchaseActivityService.isAlreadySaved(confirmation)) {
                return PurchaseSaveResult.DUPLICATE;
            }
            throw e;
        }
    }
}
