package com.loopers.domains.order.app.port;

import java.time.Duration;

public interface ReconcilePacer {
    // 관리자 일괄 조회 확정에서 PG 조회 사이에 둘 간격만큼 기다린다. 0이면 바로 돌아온다.
    void pause(Duration interval);
}
