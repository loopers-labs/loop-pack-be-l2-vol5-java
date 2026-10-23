package com.loopers.application.order;

public class OrderCommand {
    /**
     * 주문 요청 품목. 단가는 주문 시점에 상품에서 가져오므로 입력에 포함하지 않는다.
     */
    public record Item(Long productId, int quantity) {}

    /**
     * 주문 확정(PG 결제 시작) 요청. 금액은 저장된 주문에서 정하므로 입력에 없다.
     * 카드 번호는 PG 로 보내기 위해서만 들고 있으며 toString 에서도 가린다.
     */
    public record Confirm(String paymentAttemptKey, String cardType, String cardNo) {
        @Override
        public String toString() {
            return "Confirm[paymentAttemptKey=" + paymentAttemptKey
                + ", cardType=" + cardType
                + ", cardNo=****]";
        }
    }
}
