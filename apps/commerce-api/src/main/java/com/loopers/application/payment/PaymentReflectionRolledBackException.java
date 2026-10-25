package com.loopers.application.payment;

// 결과 반영(TX B)이나 0행 판정이 저장 오류로 rollback됐다. 시도·주문·재고는 바뀌지 않았고 같은 관찰을 다시 반영할 수 있다.
// TX B commit 뒤 응답용 재조회 실패나 정합성 오류(CoreException)는 이 예외로 감싸지 않는다.
public class PaymentReflectionRolledBackException extends RuntimeException {

    public PaymentReflectionRolledBackException(Throwable cause) {
        super("결제 결과 반영이 저장 오류로 취소되었습니다.", cause);
    }
}
