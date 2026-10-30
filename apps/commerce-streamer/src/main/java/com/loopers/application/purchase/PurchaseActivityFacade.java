package com.loopers.application.purchase;

import com.loopers.domain.purchase.DuplicateVerdict;
import com.loopers.domain.purchase.OrderConfirmation;
import com.loopers.domain.purchase.OrderConfirmationViolation;
import com.loopers.domain.purchase.PurchaseFact;
import com.loopers.domain.purchase.RecordAddress;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/**
 * 받은 주문 확정 기록 하나의 결말을 정한다(docs/week6/design.md 3번 소비, 5번 유스케이스). 순서만 안다 — 계약 검사는
 * domain(OrderConfirmation), 같은 사실 판정은 domain(DuplicateVerdict), 저장은 writer·recorder가 한다.
 *
 * <p>정상으로 돌아오면 호출자(listener)가 ack한다. 예외가 나가면 ack하지 않는다(INV-606). 실패는 "다시 하면 결과가 바뀌는가"로
 * 나눈다(docs/week6/decisions.md D-8):
 * <ul>
 *   <li>계약 깨짐(읽을 수 없음, 약속 위반) — 안 바뀐다. 원본을 기록하고 정상 반환(D-19, D-22).</li>
 *   <li>unique 겹침 — 롤백 뒤 새 트랜잭션에서 비교한다(D-10, D-18). 같은 사실이면 no-op, 다르면 conflict를 기록하고 정상 반환.</li>
 *   <li>그 밖의 모든 예외(DB 장애, 락 타임아웃, 다른 무결성 위반, 코드 버그) — 그대로 던진다. container가 멈추고 사람이
 *       재시작하면 같은 기록부터 다시 처리한다(D-20, D-29). 기록하고 넘기면 처리하지 않은 것을 처리했다고 하는 조용한 유실이다.</li>
 * </ul>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class PurchaseActivityFacade {

    private final OrderConfirmedMessageReader messageReader;
    private final PurchaseActivityWriter writer;
    private final PurchaseActivityDuplicateJudge duplicateJudge;
    private final DeliveryProblemRecorder problemRecorder;

    public void handle(RecordAddress address, String recordKey, byte[] value) {
        OrderConfirmation confirmation;
        try {
            confirmation = messageReader.read(recordKey, value);
        } catch (OrderConfirmationViolation violation) {
            problemRecorder.contractViolation(address, recordKey, value, violation.reason());
            return;
        }
        PurchaseFact fact = PurchaseFact.of(confirmation);
        try {
            writer.write(confirmation, fact);
            return;
        } catch (DataIntegrityViolationException e) {
            if (!DuplicateKeys.isDuplicateKey(e)) {
                throw e;
            }
            log.info("구매 활동 unique 겹침 — 새 트랜잭션에서 비교: address={}, eventId={}", address, confirmation.eventId());
        }
        DuplicateVerdict verdict = duplicateJudge.judge(confirmation, fact);
        switch (verdict) {
            case DuplicateVerdict.SameFact same ->
                log.info("이미 처리한 같은 사실 — no-op: address={}, eventId={}", address, confirmation.eventId());
            case DuplicateVerdict.Conflict conflict -> problemRecorder.conflict(address, confirmation, fact, conflict);
            case DuplicateVerdict.Unexplained unexplained -> throw new IllegalStateException(
                "unique에 걸렸는데 겹치는 기록을 찾지 못함 — 분류하지 못한 실패로 멈춘다: address=" + address
                    + ", eventId=" + confirmation.eventId());
        }
    }
}
