package com.loopers.application.order;

/**
 * relay 회차를 멈춘 DB·저장 정합성·전송 계약 오류(설계 W7.3-4). 전송 실패(SEND_*)와 다르며 행에 저장하지 않는다.
 * 메시지에는 단계·row PK·시도 번호·원인 클래스명만 담는다. 원인은 내부 진단용으로만 연결한다.
 *
 * <p>{@link Stage#MARK_PUBLISHED} 는 broker 성공 뒤 상태 저장이 실패했거나 응답이 불명인 경우다. rollback 완료로 단정하지 않으며
 * 실제 상태는 다음 조회가 판단한다.
 */
public class OrderOutboxRelayException extends RuntimeException {

    public enum Stage {
        FIND_TARGET,
        BEGIN_ATTEMPT,
        BLOCK_INVALID,
        RECORD_FAILURE,
        RECEIPT_CONTRACT,
        MARK_PUBLISHED
    }

    private final Stage stage;

    public OrderOutboxRelayException(Stage stage, Long outboxId, Long attemptNo, Throwable cause) {
        super("Outbox relay 중단. [stage=" + stage + ", outboxId=" + outboxId + ", attemptNo=" + attemptNo
            + ", causeType=" + (cause == null ? "none" : cause.getClass().getName()) + "]", cause);
        this.stage = stage;
    }

    public Stage stage() {
        return stage;
    }
}
