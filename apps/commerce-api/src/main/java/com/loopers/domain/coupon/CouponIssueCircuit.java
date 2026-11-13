package com.loopers.domain.coupon;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * DB 발급 구간의 서킷브레이커 (8주차 설계 §7-4, D15, INV-W8-13).
 * DB 는 모든 캠페인이 공유하므로 서킷은 전체에 하나다. Redis 장애는 gate 가, DB 장애는 이 서킷이 맡는다. DB 장애로는 gate 를 닫지 않는다.
 *
 * 허가(tryAcquire)는 세마포어 자리를 잡기 전에 받는다. 서킷이 열려 있으면 DB 도 세마포어도 호출하지 않고 503 으로 끝내기 위해서다.
 * 허가를 받았지만 DB 호출 없이 끝나면(세마포어 거절 등) release 로 돌려준다. 그래야 반열림의 시험 호출 수가 어긋나지 않는다.
 */
public interface CouponIssueCircuit {

    enum State {
        CLOSED, OPEN, HALF_OPEN
    }

    State state();

    /** 서킷이 마지막으로 OPEN 에서 벗어난 시각. 한 번도 열린 적이 없으면 비어 있다. Clock 기준 (§7-4 장애 후 만료 유예). */
    Optional<Instant> lastLeftOpenAt();

    /**
     * 승격 만료 기준 시각(ms). score 가 이 값보다 작은 승격은 만료다.
     * 평소에는 now - admittedTtl 이고, 서킷이 OPEN 에서 벗어난 뒤 admittedTtl 이 지나기 전까지는 0 이다(아무도 만료시키지 않는다).
     * DB 장애 동안 다시 시도하지 않은 승격자가 사용자 잘못 없이 차례를 잃는 것을 막는다 (§7-4).
     */
    default long admittedCutoffMillis(Instant now, Duration admittedTtl) {
        boolean inGrace = lastLeftOpenAt()
            .map(leftAt -> Duration.between(leftAt, now).compareTo(admittedTtl) < 0)
            .orElse(false);
        return inGrace ? 0L : now.toEpochMilli() - admittedTtl.toMillis();
    }

    /**
     * @return 호출을 허가하면 true. 열려 있거나 반열림 시험 호출 수를 다 썼으면 false
     */
    boolean tryAcquire();

    /** 허가를 받았지만 DB 를 호출하지 않고 끝날 때 허가를 돌려준다. */
    void release();

    void recordSuccess();

    /**
     * 실패 후보를 기록한다. DataAccessException 계열만 실패로 세고, CoreException(품절·종료·없음 등 업무 결과)은 세지 않는다.
     */
    void recordFailure(Throwable cause);
}
