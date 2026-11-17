package com.loopers.coupon.application.port.out;

import java.util.Set;

/**
 * Redis에 있는 판정 상태 — 상태 키, 당첨자 Set, 줄(ZSET), 입장 키 (D-4, D-2). 판정은 모두 Lua 한 번으로 한다.
 * Redis에 닿지 못하거나 시간이 넘으면 {@link GateUnavailableException} — 우회하지 않는다.
 */
public interface CouponGatePort {

    IssueDecision issue(Long campaignId, Long userId, int totalQuantity);

    QueueState register(Long campaignId, Long userId, int totalQuantity, int queueCapacity);

    QueueState status(Long campaignId, Long userId, int totalQuantity);

    AdmitResult admit(Long campaignId, int totalQuantity);

    /** 복구를 한 서버만 하도록 잠근다 (SET NX EX 10). 얻었으면 true */
    boolean tryLockRebuild(Long campaignId);

    /** 당첨자 Set을 다시 채우고 상태 키를 쓴다 (한 Lua) */
    void rebuild(Long campaignId, Set<Long> winners);

    Set<Long> winners(Long campaignId);

    void deleteEntry(Long campaignId, Long userId);

    /** 마감 뒤 정리 — 줄·순번 카운터·당첨자 Set·상태 키 (D-4-6) */
    void cleanup(Long campaignId);

    enum IssueDecision { UNKNOWN, ALREADY, NOT_ADMITTED, SOLD_OUT, ACCEPTED }

    enum QueueStateType { UNKNOWN, ISSUED, SOLD_OUT, ADMITTED, WAITING, QUEUE_FULL, NOT_IN_QUEUE }

    /** @param ahead 내 앞 인원 (WAITING일 때만, 아니면 -1) */
    record QueueState(QueueStateType type, long ahead) {}

    enum AdmitStatus { UNKNOWN, SOLD_OUT, OK }

    record AdmitResult(AdmitStatus status, int admitted) {}

    class GateUnavailableException extends RuntimeException {
        public GateUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
