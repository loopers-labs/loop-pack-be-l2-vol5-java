package com.loopers.support.pg;

/**
 * 테스트 PG에 저장된 거래 하나다. 카드번호는 담지 않는다.
 *
 * @param account  생성 요청의 {@code X-USER-ID}. 같은 계정의 조회에만 보인다
 * @param sequence 생성 순서. 주문별 조회는 이 순서로 돌려준다
 */
public record TestPgTransaction(
    String transactionKey, String account, String orderId, long amount, TestPgStatus status, String cardType, long sequence
) {
    TestPgTransaction withStatus(TestPgStatus next) {
        return new TestPgTransaction(transactionKey, account, orderId, amount, next, cardType, sequence);
    }
}
