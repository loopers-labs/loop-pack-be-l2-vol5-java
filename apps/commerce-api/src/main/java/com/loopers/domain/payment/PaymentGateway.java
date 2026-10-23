package com.loopers.domain.payment;

/**
 * 외부 결제(PG) 포트. 저장소나 로컬 TX 를 호출하지 않으며 TX 밖에서 호출한다(설계 2.7).
 * 통신 실패·해석 불가는 예외 대신 확인 불가 관찰로 돌려준다. 원인을 식별하지 못한 내부 오류는 전파한다.
 * 생성은 호출마다 POST 를 최대 한 번, 조회는 GET 을 최대 한 번 보내고 재시도하지 않는다.
 */
public interface PaymentGateway {

    /** HTTP·DB 없이 생성 회로가 지금 차단 중인지만 본다. 얻은 권한은 즉시 돌려주며 TX A 동안 보유하지 않는다. */
    CreateAdmission checkCreateAdmission();

    /**
     * TX A commit 뒤 호출한다. 실제 회로 권한을 다시 얻어 POST 한다. 그 권한을 얻지 못했거나 이 POST 의 최초 풀 획득이
     * 실패해 요청이 확실히 나가지 않았을 때만 로컬 미발송 관찰을 돌려준다.
     */
    PaymentObservation create(PaymentRequest request);

    PaymentObservation findByTransactionKey(String transactionKey);

    PaymentObservation findByOrderId(String providerOrderId);
}
