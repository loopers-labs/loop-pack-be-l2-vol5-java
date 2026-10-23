package com.loopers.fixture.pg;

import java.util.List;

/**
 * sendCallbacks 한 번의 모든 전송 결과(attempt 순). 완료는 모든 전송 시도가 끝났다는 뜻일 뿐
 * 커머스 업무 완료가 아니다. 정상 콜백 검증은 각 전송의 기대 ACK 뒤 새 API/DB 조회로 한다(5.7).
 */
public record CallbackBatchResult(String transactionKey, List<CallbackDelivery> deliveries) {

    public CallbackBatchResult {
        deliveries = List.copyOf(deliveries);
    }
}
