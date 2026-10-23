package com.loopers.support.fixture;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 이미 확정(CONFIRMED)된 주문을 준비한다. 결제 수단이 포인트에서 PG 로 바뀌어 주문 확정은 PG 결과를 확인해야 일어나므로,
 * 확정된 주문이 필요할 뿐인 조회·관리자 테스트는 확정 시점의 결과를 그대로 저장해 둔다. 결제액은 주문 합계다.
 *
 * <p>확정하는 동작 자체를 검증하는 테스트에서 쓰지 않는다. 그런 테스트는 결제 시작·결과 반영 흐름을 실제로 지난다.
 */
public final class ConfirmedOrders {

    private ConfirmedOrders() {
    }

    public static void confirm(JdbcTemplate jdbcTemplate, Long orderId) {
        int updated = jdbcTemplate.update(
            "update orders set status = 'CONFIRMED', paid_amount = total_amount where id = ?", orderId
        );
        if (updated != 1) {
            throw new IllegalStateException("확정할 주문이 없습니다. (주문 ID: " + orderId + ")");
        }
    }
}
