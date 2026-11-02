package com.loopers.application.purchase;

import com.loopers.domain.purchase.DuplicateVerdict;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.OrderConfirmedContractViolation;
import com.loopers.domain.purchase.OrderConfirmedContractViolationRepository;
import com.loopers.domain.purchase.PurchaseActivityConflict;
import com.loopers.domain.purchase.PurchaseActivityConflictRepository;
import com.loopers.domain.purchase.PurchaseFact;
import com.loopers.domain.purchase.RecordAddress;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.time.ZonedDateTime;
import java.util.function.Supplier;

/**
 * 다시 해도 안 풀리는 실패(conflict, 계약 깨짐)를 조용히 버리지 않고 기록한다(INV-607, docs/week6/decisions.md D-8, D-9, D-22).
 * 기록이 끝나면 호출자는 ack한다 — 멈추면 같은 자리에서 영원히 멈추고 같은 partition의 다른 주문까지 막힌다(poison pill).
 *
 * - 기록마다 자기 트랜잭션이다. 이 클래스에는 트랜잭션을 걸지 않고 리포지토리의 insert(saveAndFlush) 한 번이 한 트랜잭션이다.
 *   여기에 트랜잭션을 걸면 주소 unique 예외가 그 트랜잭션을 rollback-only로 만들어, 아래처럼 "이미 기록됨"으로 넘어갈 수 없다.
 * - 주소 unique에 걸리면 "이미 기록됨"이다. 기록 뒤 ack 전에 죽어 같은 기록을 다시 받은 경우라 성공으로 본다(D-22).
 * - Counter `purchase.activity.rejected{kind}`는 <b>새로 기록한 행</b>마다 하나 올린다(D-30). 같은 주소의 재전달은 이미 센
 *   사건이라 다시 세지 않는다 — 지표가 "몇 번 받았나"가 아니라 "기록할 문제가 몇 건 생겼나"를 말하게 한다.
 *   기록하고 ack한 기록은 lag에 드러나지 않으므로, 이 지표가 없으면 테이블만 쌓이고 아무도 모를 수 있다.
 */
@Slf4j
@Component
public class DeliveryProblemRecorder {

    public static final String METRIC = "purchase.activity.rejected";
    public static final String KIND_TAG = "kind";
    public static final String KIND_CONFLICT = "conflict";
    public static final String KIND_CONTRACT_VIOLATION = "contract_violation";

    private final PurchaseActivityConflictRepository conflictRepository;
    private final OrderConfirmedContractViolationRepository contractViolationRepository;
    private final Counter conflicts;
    private final Counter contractViolations;

    public DeliveryProblemRecorder(PurchaseActivityConflictRepository conflictRepository,
                                   OrderConfirmedContractViolationRepository contractViolationRepository,
                                   MeterRegistry meterRegistry) {
        this.conflictRepository = conflictRepository;
        this.contractViolationRepository = contractViolationRepository;
        this.conflicts = counter(meterRegistry, KIND_CONFLICT);
        this.contractViolations = counter(meterRegistry, KIND_CONTRACT_VIOLATION);
    }

    private static Counter counter(MeterRegistry meterRegistry, String kind) {
        return Counter.builder(METRIC)
            .description("기록하고 넘긴 주문 확정 기록 수 (week6 D-30)")
            .tag(KIND_TAG, kind)
            .register(meterRegistry);
    }

    public void conflict(RecordAddress address, OrderConfirmation incoming, PurchaseFact newFact, DuplicateVerdict.Conflict conflict) {
        boolean recorded = insertOnce(() -> conflictRepository.insert(
            new PurchaseActivityConflict(address, incoming, newFact, conflict, ZonedDateTime.now())));
        if (!recorded) {
            log.info("구매 활동 conflict 이미 기록됨: address={}, orderId={}", address, incoming.orderId());
            return;
        }
        conflicts.increment();
        log.warn("구매 활동 conflict 기록: address={}, orderId={}, eventId={}, reason={}, firstFact={}, newFact={}",
            address, incoming.orderId(), incoming.eventId(), conflict.reason(), conflict.firstFact().value(), newFact.value());
    }

    public void contractViolation(RecordAddress address, String recordKey, byte[] rawValue, String reason) {
        byte[] raw = rawValue == null ? new byte[0] : rawValue;
        boolean recorded = insertOnce(() -> contractViolationRepository.insert(
            new OrderConfirmedContractViolation(address, recordKey, raw, reason, ZonedDateTime.now())));
        if (!recorded) {
            log.info("주문 확정 계약 깨짐 이미 기록됨: address={}", address);
            return;
        }
        contractViolations.increment();
        log.warn("주문 확정 계약 깨짐 기록: address={}, key={}, reason={}", address, recordKey, reason);
    }

    /** 새로 기록했으면 true, 같은 주소가 이미 있으면 false. 그 밖의 실패는 그대로 던진다(D-29). */
    private static boolean insertOnce(Supplier<?> insert) {
        try {
            insert.get();
            return true;
        } catch (DataIntegrityViolationException e) {
            if (DuplicateKeys.isDuplicateKey(e)) {
                return false;
            }
            throw e;
        }
    }
}
