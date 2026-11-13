package com.loopers.application.coupon;

/**
 * 쿠폰 신규 발급의 동시 실행 수(C)를 세는 세마포어. 쿠폰이나 재고는 판단하지 않는다.
 * 자리는 실행이 끝난 뒤에만 자기 token으로 반환하며, TTL 같은 시간 경과로 자동 회수하지 않는다.
 */
public interface SemaphoreGate {

    /** 자리를 얻으려 한다. 실행마다 서버가 새 token을 만든다. */
    Admission acquire();

    /** 자기 token만 반환한다. 이미 반환했거나 모르는 token이어도 다른 실행의 자리는 건드리지 않는다. */
    void release(Admission admission);

    /**
     * 반환하지 못했거나 획득 응답을 받지 못해 Redis에 남았을 수 있는 자기 token과, 이전 프로세스가 남긴 자기 인스턴스 token을 정리한다.
     * Redis가 안 되면 예외 없이 멈추고 다음 호출에서 다시 한다. 다른 인스턴스·진행 중인 실행의 token과 Set 전체는 지우지 않는다.
     */
    void recoverOwnTokens();

    /**
     * @param status 획득 결과
     * @param token  이번 실행의 token. 획득했거나 결과가 불확실할 때 반환 대상이 된다
     */
    record Admission(Status status, String token) {
    }

    enum Status {
        /** 자리를 얻었다. */
        ACQUIRED,
        /** 자리가 없다(수용량 초과). DB 경로에 들어가지 않는다. */
        FULL,
        /** Redis 오류나 timeout으로 결과를 모른다. token이 Set에 들어갔을 수 있다. */
        UNCERTAIN,
        /** CircuitBreaker가 Redis 호출을 막아 호출하지 않았다. token이 만들어지지 않았고 Set에도 없다. */
        SKIPPED
    }
}
