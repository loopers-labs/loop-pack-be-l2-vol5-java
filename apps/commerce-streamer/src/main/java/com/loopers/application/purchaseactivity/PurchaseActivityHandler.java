package com.loopers.application.purchaseactivity;

import com.loopers.domain.purchaseactivity.OrderConfirmation;
import com.loopers.support.persistence.PurchaseActivityConstraintClassifier;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * TX 를 열지 않고 PurchaseActivityTransactionService 를 호출한다(소비 설계 5절). 반환은 그 TX 가 실제로 끝난 뒤다.
 * 완료 unique 위반만 쓰기 TX 의 rollback 이 끝난 뒤 새 readOnly TX 에서 같은 사실인지 다시 본다.
 * 활동 unique·CHECK·식별 불가 제약·deadlock·연결 오류는 정상 중복이 아니므로 그대로 전파한다.
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityHandler {

    private final PurchaseActivityTransactionService transactions;
    private final PurchaseActivityConstraintClassifier classifier;

    public PurchaseActivityResult consume(OrderConfirmation event) {
        // 상위 TX 에 참여하면 제약 위반 뒤 rollback 이 끝나지 않은 채 재확인하게 되어 경계가 무너진다.
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("구매 활동 처리는 상위 트랜잭션 안에서 호출할 수 없습니다.");
        }
        try {
            return transactions.consume(event);
        } catch (DataIntegrityViolationException failure) {
            if (classifier.completionUniqueViolation(failure).isEmpty()) {
                throw failure;
            }
            return transactions.verifyDuplicate(event, failure);
        }
    }
}
