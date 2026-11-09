package com.loopers.application.purchase;

import com.loopers.domain.purchase.OrderConfirmation;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 확정 주문 이벤트를 구매 활동으로 반영한다. 이 클래스에는 트랜잭션이 없고, DB를 바꾸는 단계는 {@link PurchaseActivityRecorder}의 트랜잭션이다.
 * {@link #consume}이 돌아왔다면 DB 트랜잭션은 이미 commit(또는 이미 반영돼 있음)된 것이라, 호출자는 그 뒤에 ack할 수 있다.
 *
 * <p>예외는 삼키지 않는다. 충돌·검증 실패·DB 오류는 그대로 올라가 listener가 ack하지 않게 한다.
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivityHandler {

    private final PurchaseActivityRecorder recorder;

    public PurchaseActivityRecorder.Result consume(OrderConfirmation confirmation) {
        try {
            return recorder.record(confirmation);
        } catch (DataIntegrityViolationException race) {
            // 동시에 같은 이벤트를 소비해 유일 제약에 걸렸다. 실패한 트랜잭션은 rollback됐고, 새 트랜잭션에서 기존 사실과 같은지 본다.
            // 기존 사실이 없으면 중복이 아니라 다른 무결성 오류이므로 "중복"으로 바꾸지 않고 그대로 올린다
            return recorder.confirmExisting(confirmation).orElseThrow(() -> race);
        }
    }
}
