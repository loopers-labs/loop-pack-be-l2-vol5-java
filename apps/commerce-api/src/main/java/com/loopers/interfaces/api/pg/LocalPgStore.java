package com.loopers.interfaces.api.pg;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 로컬 테스트 PG 인메모리 저장소.
 * 시나리오 제어 (amount 기반):
 *   amount == 1 → REJECTED (422, 거래 미생성)
 *   amount == 2 → TIMEOUT  (POST 지연, 거래는 SUCCESS로 저장 → 대사 시 조회 가능)
 *   amount == 3 → 최종 상태 FAILED
 *   그 외       → 최종 상태 SUCCESS
 */
@Component
public class LocalPgStore {

    public static final long SCENARIO_REJECTED = 1L;
    public static final long SCENARIO_TIMEOUT = 2L;
    public static final long SCENARIO_FAILED = 3L;

    private final Map<String, LocalPgDto.PaymentResponse> byTransactionKey = new ConcurrentHashMap<>();
    private final Map<String, List<LocalPgDto.PaymentResponse>> byOrderId = new ConcurrentHashMap<>();

    public boolean isRejected(long amount) {
        return amount == SCENARIO_REJECTED;
    }

    public boolean isTimeout(long amount) {
        return amount == SCENARIO_TIMEOUT;
    }

    public Optional<LocalPgDto.PaymentResponse> findByTransactionKey(String transactionKey) {
        return Optional.ofNullable(byTransactionKey.get(transactionKey));
    }

    public List<LocalPgDto.PaymentResponse> findByOrderId(String orderId) {
        return byOrderId.getOrDefault(orderId, List.of());
    }

    public LocalPgDto.PaymentResponse store(String orderId, long amount) {
        String finalStatus = (amount == SCENARIO_FAILED) ? "FAILED" : "SUCCESS";
        String transactionKey = "TEST-TX-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        LocalPgDto.PaymentResponse response = new LocalPgDto.PaymentResponse(
            transactionKey, orderId, amount, finalStatus
        );
        byTransactionKey.put(transactionKey, response);
        byOrderId.computeIfAbsent(orderId, k -> new ArrayList<>()).add(response);
        return response;
    }

    public void clear() {
        byTransactionKey.clear();
        byOrderId.clear();
    }
}
