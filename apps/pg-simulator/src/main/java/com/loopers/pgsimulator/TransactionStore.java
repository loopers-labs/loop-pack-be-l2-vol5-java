package com.loopers.pgsimulator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 메모리 거래 저장소. 잠금은 메모리 연산 동안만 잡고 응답·콜백 전송은 호출자가 잠금 밖에서 한다.
 * 같은 orderId 의 생성도 멱등 처리하지 않고 새 거래를 추가한다(5.5). 생성은 HTTP 요청으로만 한다.
 */
public final class TransactionStore {

    private final Map<String, TransactionSnapshot> transactionsByKey = new LinkedHashMap<>();
    private final Map<String, List<String>> transactionKeysByOrderId = new HashMap<>();

    synchronized TransactionSnapshot create(String orderId, long amount, String callbackUrl, PaymentStatus status) {
        TransactionSnapshot transaction = new TransactionSnapshot(
            UUID.randomUUID().toString(), orderId, amount, status, callbackUrl);
        transactionsByKey.put(transaction.transactionKey(), transaction);
        transactionKeysByOrderId.computeIfAbsent(orderId, id -> new ArrayList<>()).add(transaction.transactionKey());
        return transaction;
    }

    /** 없는 거래는 IllegalArgumentException 이다. 콜백을 보내지 않는다. */
    public synchronized void setStatus(String transactionKey, PaymentStatus status) {
        if (status == null) {
            throw new IllegalArgumentException("status 필수");
        }
        TransactionSnapshot transaction = transactionsByKey.get(transactionKey);
        if (transaction == null) {
            throw new IllegalArgumentException("없는 거래: " + transactionKey);
        }
        transactionsByKey.put(transactionKey, transaction.withStatus(status));
    }

    public synchronized Optional<TransactionSnapshot> findByKey(String transactionKey) {
        return Optional.ofNullable(transactionsByKey.get(transactionKey));
    }

    public synchronized List<TransactionSnapshot> findByOrderId(String orderId) {
        return transactionKeysByOrderId.getOrDefault(orderId, List.of()).stream()
            .map(transactionsByKey::get)
            .toList();
    }

    public synchronized List<TransactionSnapshot> findAll() {
        return List.copyOf(transactionsByKey.values());
    }

    public synchronized void clear() {
        transactionsByKey.clear();
        transactionKeysByOrderId.clear();
    }
}
