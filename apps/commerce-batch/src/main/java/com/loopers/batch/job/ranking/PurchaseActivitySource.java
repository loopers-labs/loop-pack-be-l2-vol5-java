package com.loopers.batch.job.ranking;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

/**
 * W6 · W7 consumer 가 남긴 구매 활동을 읽는다. 일반 SELECT 라 잠금 없이 TX 의 read view 로 읽는다 (9주차 D-177).
 * DATETIME 은 UTC 벽시계 값으로 저장돼 있어, 드라이버의 시간대 변환을 피하려고 LocalDateTime 으로 주고받는다
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivitySource {

    private final JdbcTemplate jdbc;

    /** 확정 시각이 [start, end) 인 주문의 모든 품목 (D-176) */
    public List<PurchaseInput> findConfirmedBetween(LocalDate start, LocalDate end) {
        return jdbc.query("""
                select a.order_id, a.product_id, a.quantity, c.confirmed_at
                from processed_order_confirmation c
                join product_purchase_activity a on a.order_id = c.order_id
                where c.confirmed_at >= ? and c.confirmed_at < ?
                order by a.order_id, a.product_id""",
            (rs, rowNum) -> new PurchaseInput(
                rs.getLong("order_id"),
                rs.getLong("product_id"),
                rs.getInt("quantity"),
                rs.getObject("confirmed_at", LocalDateTime.class)
            ),
            start.atStartOfDay(), end.atStartOfDay());
    }
}
