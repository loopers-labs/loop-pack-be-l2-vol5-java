package com.loopers.domain.support.error;

// 도메인 계층 에러 코드
public enum DomainErrorCode {
    INVALID_USER_ID("사용자 ID는 양의 정수여야 합니다."),
    INVALID_MONEY("금액은 0 이상이어야 합니다."),
    NON_POSITIVE_MONEY("금액은 양의 정수여야 합니다."),
    CALCULATION_OVERFLOW("계산 결과가 허용 범위를 초과했습니다."),
    INVALID_STOCK("재고는 0 이상이어야 합니다."),
    INVALID_QUANTITY("수량은 양의 정수여야 합니다."),
    INSUFFICIENT_STOCK("상품 재고가 부족합니다."),
    INVALID_NAME("이름은 앞뒤 공백을 제외하고 1자 이상 100자 이하여야 합니다."),
    INVALID_DESCRIPTION("설명은 1,000자 이하여야 합니다."),
    DELETED_BRAND("브랜드를 찾을 수 없습니다."),
    DELETED_PRODUCT("상품을 찾을 수 없습니다."),
    EMPTY_ORDER_ITEMS("주문은 한 품목 이상 포함해야 합니다."),
    ORDER_ALREADY_CONFIRMED("이미 확정된 주문입니다."),
    ORDER_PAYMENT_NOT_ALLOWED("현재 주문 상태에서는 결제를 변경할 수 없습니다."),
    INVALID_PAYMENT_REQUEST("결제 요청의 키·합성 카드·테스트 조건을 확인해주세요."),
    INVALID_PAYMENT_CALLBACK("결제 알림의 주문·거래 키·상태를 확인해주세요."),
    INSUFFICIENT_POINT("포인트가 부족합니다."),
    PAYMENT_DISPATCH_NOT_ALLOWED("결제 요청을 새로 발송할 수 없는 상태입니다."),
    PAYMENT_RECOVERY_NOT_ALLOWED("복구 작업의 실행 권한 또는 조회 예산이 유효하지 않습니다."),
    STOCK_ADJUSTMENT_ALREADY_FINISHED("이미 종결한 재고 처리 결과를 바꿀 수 없습니다."),
    PAYMENT_OBSERVATION_NOT_ALLOWED("발송 시작을 기록한 결제 시도에만 결과를 반영할 수 있습니다.");

    private final String message;

    DomainErrorCode(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
