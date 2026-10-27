package com.loopers.infrastructure.payment;

import com.loopers.domain.payment.PaymentAttempt;
import com.loopers.domain.payment.PaymentCreateCommand;
import com.loopers.domain.payment.PaymentCreateResult;
import com.loopers.domain.payment.PaymentGateway;
import com.loopers.domain.payment.PaymentLookupResult;
import com.loopers.domain.payment.PaymentTransaction;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 테스트 전용 가짜 PG. 운영 빈이 아니며 FakePaymentGatewayConfig 를 @Import 한 테스트에서만 쓴다.
 * 다음 create 의 결과를 테스트가 미리 정하고, create 호출 횟수·저장된 거래·거래 상태 변경을 관찰·조작할 수 있다.
 * 무작위 지연·확률은 없고, 카드 번호는 저장하지 않으며, 로그도 남기지 않는다.
 */
public class FakePaymentGateway implements PaymentGateway {

    /**
     * ACCEPT_PENDING: 거래 생성 + 202 PENDING 수락.
     * REJECT_NO_EFFECT: 거래 없이 효과 0건 확정 거절.
     * CREATE_BUT_LOSE_RESPONSE: 거래는 만들지만 응답이 유실되어 UNKNOWN 을 돌려준다.
     * ACCEPT_WITH_MISMATCHED_AMOUNT: 202 인데 본문 금액이 요청과 다르다. 거래는 그 다른 금액으로 생기고 UNKNOWN + 충돌 사유를 돌려준다 (설계 결정 ㉕).
     * REJECT_WITH_MISMATCHED_ORDER_ID: 422 + 정확한 code + false 인데 orderId 가 다르다. 거래 없이 UNKNOWN + 충돌 사유를 돌려준다 (설계 결정 ⑱).
     */
    public enum NextCreate {
        ACCEPT_PENDING, REJECT_NO_EFFECT, CREATE_BUT_LOSE_RESPONSE, ACCEPT_WITH_MISMATCHED_AMOUNT, REJECT_WITH_MISMATCHED_ORDER_ID
    }

    private static final String TRANSACTION_KEY_FORMAT = "FAKE-TX-%06d";

    private volatile NextCreate nextCreate = NextCreate.ACCEPT_PENDING;
    private volatile boolean failLookups;
    private volatile boolean circuitOpen;
    private volatile String throwOnLookupOf;
    private volatile boolean lookupSeenInsideTransaction;
    private final AtomicInteger createCount = new AtomicInteger();
    private final AtomicInteger lookupCallCount = new AtomicInteger();
    private final AtomicLong sequence = new AtomicLong();
    private final ConcurrentMap<String, PaymentTransaction> transactions = new ConcurrentHashMap<>();

    public void willAcceptPending() {
        nextCreate = NextCreate.ACCEPT_PENDING;
    }

    public void willRejectNoEffect() {
        nextCreate = NextCreate.REJECT_NO_EFFECT;
    }

    public void willCreateButLoseResponse() {
        nextCreate = NextCreate.CREATE_BUT_LOSE_RESPONSE;
    }

    public void willAcceptWithMismatchedAmount() {
        nextCreate = NextCreate.ACCEPT_WITH_MISMATCHED_AMOUNT;
    }

    public void willRejectWithMismatchedOrderId() {
        nextCreate = NextCreate.REJECT_WITH_MISMATCHED_ORDER_ID;
    }

    /**
     * 이후 조회(목록·단건)가 전부 UNAVAILABLE(CALL_FAILED, timeout 흉내)이 된다. 거래는 그대로 있다.
     */
    public void willFailLookups() {
        failLookups = true;
    }

    /**
     * 이후 조회(목록·단건)가 전부 UNAVAILABLE(CIRCUIT_OPEN, 서킷 차단 흉내)이 된다. 호출은 lookupCallCount 에 센다.
     * 실제 adapter 에서는 서킷이 HTTP 호출을 막지만, Facade 가 보는 것은 결과 타입뿐이라 가짜는 결과만 흉내 낸다.
     */
    public void willOpenCircuit() {
        circuitOpen = true;
    }

    /**
     * 조회 실패·서킷 차단 흉내를 모두 풀어 정상 조회로 돌아간다. 거래·호출 횟수는 그대로다 ("회복 뒤" 장면용).
     */
    public void willServeLookups() {
        failLookups = false;
        circuitOpen = false;
    }

    /**
     * 특정 주문의 목록 조회에서만 RuntimeException 을 던진다. "한 건 예외가 다음 건을 막지 않는다" 장면용이며 UNAVAILABLE 과 다르다.
     */
    public void willThrowOnLookupOf(String providerOrderId) {
        throwOnLookupOf = providerOrderId;
    }

    public int createCount() {
        return createCount.get();
    }

    /**
     * PG 조회(목록·단건) 호출 횟수. 결제 시도의 lookupCount(스케줄러 조회 횟수)와 다른 값이다.
     */
    public int lookupCallCount() {
        return lookupCallCount.get();
    }

    /**
     * 어떤 조회 호출이든 DB 트랜잭션 안에서 들어온 적이 있으면 true. "PG 호출 중 TX 미보유" 장면용.
     */
    public boolean lookupSeenInsideTransaction() {
        return lookupSeenInsideTransaction;
    }

    /**
     * 저장된 거래 전부. 키가 "FAKE-TX-000001" 처럼 순번이라 키 정렬이 곧 생성 순서다.
     */
    public List<PaymentTransaction> transactions() {
        return transactions.values().stream()
            .sorted(Comparator.comparing(PaymentTransaction::transactionKey))
            .toList();
    }

    public void markSuccess(String transactionKey) {
        changeStatus(transactionKey, PaymentTransaction.Status.SUCCESS);
    }

    public void markFailed(String transactionKey) {
        changeStatus(transactionKey, PaymentTransaction.Status.FAILED);
    }

    public void reset() {
        nextCreate = NextCreate.ACCEPT_PENDING;
        failLookups = false;
        circuitOpen = false;
        throwOnLookupOf = null;
        lookupSeenInsideTransaction = false;
        createCount.set(0);
        lookupCallCount.set(0);
        sequence.set(0);
        transactions.clear();
    }

    @Override
    public PaymentCreateResult create(PaymentCreateCommand command) {
        createCount.incrementAndGet();
        return switch (nextCreate) {
            case REJECT_NO_EFFECT -> PaymentCreateResult.rejectedNoEffect();
            case ACCEPT_PENDING -> PaymentCreateResult.accepted(store(command).transactionKey());
            case CREATE_BUT_LOSE_RESPONSE -> {
                store(command);
                yield PaymentCreateResult.unknown();
            }
            case ACCEPT_WITH_MISMATCHED_AMOUNT -> {
                store(command.providerOrderId(), command.amount() + 1);
                yield PaymentCreateResult.unknownWithConflict(PaymentAttempt.ConflictReason.AMOUNT_MISMATCH);
            }
            case REJECT_WITH_MISMATCHED_ORDER_ID -> PaymentCreateResult.unknownWithConflict(PaymentAttempt.ConflictReason.ORDER_MISMATCH);
        };
    }

    @Override
    public PaymentLookupResult findAllByOrderId(String providerOrderId) {
        observeLookup();
        if (providerOrderId.equals(throwOnLookupOf)) {
            throw new IllegalStateException("테스트: 가짜 PG 조회 예외 " + providerOrderId);
        }
        if (circuitOpen) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CIRCUIT_OPEN);
        }
        if (failLookups) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
        }
        return PaymentLookupResult.found(transactions().stream()
            .filter(transaction -> transaction.providerOrderId().equals(providerOrderId))
            .toList());
    }

    /**
     * 목록 조회와 같은 규칙이다. 있으면 FOUND + 1건, 없으면 FOUND + 0건, willOpenCircuit()·willFailLookups() 뒤에는 UNAVAILABLE.
     */
    @Override
    public PaymentLookupResult findByTransactionKey(String transactionKey) {
        observeLookup();
        if (circuitOpen) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CIRCUIT_OPEN);
        }
        if (failLookups) {
            return PaymentLookupResult.unavailable(PaymentLookupResult.UnavailableReason.CALL_FAILED);
        }
        PaymentTransaction transaction = transactions.get(transactionKey);
        return PaymentLookupResult.found(transaction == null ? List.of() : List.of(transaction));
    }

    private void observeLookup() {
        lookupCallCount.incrementAndGet();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            lookupSeenInsideTransaction = true;
        }
    }

    private PaymentTransaction store(PaymentCreateCommand command) {
        return store(command.providerOrderId(), command.amount());
    }

    private PaymentTransaction store(String providerOrderId, long amount) {
        String key = String.format(TRANSACTION_KEY_FORMAT, sequence.incrementAndGet());
        PaymentTransaction transaction = new PaymentTransaction(key, providerOrderId, amount, PaymentTransaction.Status.PENDING);
        transactions.put(key, transaction);
        return transaction;
    }

    private void changeStatus(String transactionKey, PaymentTransaction.Status status) {
        PaymentTransaction changed = transactions.computeIfPresent(transactionKey,
            (key, found) -> new PaymentTransaction(key, found.providerOrderId(), found.amount(), status));
        if (changed == null) {
            throw new IllegalArgumentException("가짜 PG 에 없는 거래다: " + transactionKey);
        }
    }
}