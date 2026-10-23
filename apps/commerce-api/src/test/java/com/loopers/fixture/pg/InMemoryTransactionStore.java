package com.loopers.fixture.pg;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 잠금은 메모리 연산 동안만 잡는다. 요청 본문 수신·응답 전송·대기는 호출자가 이 잠금 밖에서 한다(5.7).
 * 같은 orderId 의 생성도 멱등 처리하지 않고 새 거래를 추가한다(5.5).
 */
final class InMemoryTransactionStore {

    private final Map<String, StoredTransaction> transactionsByKey = new LinkedHashMap<>();
    private final Map<String, List<String>> transactionKeysByOrderId = new HashMap<>();

    synchronized StoredTransaction create(String orderId, long amount, String callbackUrl, PaymentStatus status) {
        StoredTransaction transaction = new StoredTransaction(
            UUID.randomUUID().toString(), orderId, amount, status, callbackUrl);
        transactionsByKey.put(transaction.transactionKey(), transaction);
        transactionKeysByOrderId.computeIfAbsent(orderId, id -> new ArrayList<>()).add(transaction.transactionKey());
        return transaction;
    }

    synchronized void setStatus(String transactionKey, PaymentStatus status) {
        StoredTransaction transaction = transactionsByKey.get(transactionKey);
        if (transaction == null) {
            throw new IllegalArgumentException("없는 거래: " + transactionKey);
        }
        transactionsByKey.put(transactionKey, transaction.withStatus(status));
    }

    synchronized Optional<StoredTransaction> findByKey(String transactionKey) {
        return Optional.ofNullable(transactionsByKey.get(transactionKey));
    }

    synchronized List<StoredTransaction> findByOrderId(String orderId) {
        return transactionKeysByOrderId.getOrDefault(orderId, List.of()).stream()
            .map(transactionsByKey::get)
            .toList();
    }

    synchronized List<StoredTransaction> findAll() {
        return List.copyOf(transactionsByKey.values());
    }

    synchronized void clear() {
        transactionsByKey.clear();
        transactionKeysByOrderId.clear();
    }
}
