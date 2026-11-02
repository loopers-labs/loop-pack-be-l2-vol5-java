package com.loopers.application.purchase.facade;

import com.loopers.domain.purchase.enums.PurchaseRecordResult;
import com.loopers.domain.purchase.service.PurchaseActivityService;
import com.loopers.domain.purchase.vo.OrderConfirmation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

/** 저장 TX 와 재판정 TX 를 나눔. 유일 제약 위반이 난 TX 는 rollback 만 가능해 그 안에서 다시 읽고 commit 할 수 없음 (6주차 D-85) */
@Slf4j
@RequiredArgsConstructor
@Component
public class PurchaseActivityFacade {

    private final PurchaseActivityService purchaseActivityService;

    /** 반환되면 DB commit 이 끝난 것. 그 뒤에만 ack 함 */
    public PurchaseRecordResult record(OrderConfirmation confirmation) {
        try {
            return purchaseActivityService.record(confirmation);
        } catch (DataIntegrityViolationException e) {
            // 새 TX 는 경합 상대의 commit 뒤에 시작해 그 기록을 봄. 기록이 없으면 경합이 아닌 다른 오류라 그대로 던짐
            return purchaseActivityService.judge(confirmation).orElseThrow(() -> {
                log.warn("유일 제약 위반 뒤 다시 읽었지만 처리 기록이 없어 다른 오류로 봄. eventId={}", confirmation.eventId());
                return e;
            });
        }
    }
}
