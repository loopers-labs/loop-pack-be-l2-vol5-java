package com.loopers.batch.job.ranking.freeze;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.function.Consumer;

/**
 * W6 · W7 consumer 가 남긴 구매 활동을 읽는다. 일반 SELECT 라 잠금 없이 TX 의 read view 로 읽는다 (9주차 D-177).
 * DATETIME 은 UTC 벽시계 값으로 저장돼 있어, 드라이버의 시간대 변환을 피하려고 LocalDateTime 으로 주고받는다
 */
@RequiredArgsConstructor
@Component
public class PurchaseActivitySource {

    // 기본 계획은 confirmation 전체를 조인 · 정렬한 뒤 limit 해 페이지마다 끝까지 읽는다. 인덱스 순서로 읽고 limit 에서 멈추게 강제한다
    private static final String PAGE_SQL = """
        select straight_join a.order_id, a.product_id, a.quantity, c.confirmed_at
        from product_purchase_activity a force index (uk_product_purchase_activity_order_product)
        join processed_order_confirmation c on c.order_id = a.order_id
        where (a.order_id > ? or (a.order_id = ? and a.product_id > ?))
          and c.confirmed_at >= ? and c.confirmed_at < ?
        order by a.order_id, a.product_id
        limit ?""";

    private final JdbcTemplate jdbc;

    /**
     * 확정 시각이 [start, end) 인 주문의 모든 품목을 (orderId, productId) 순 페이지로 넘긴다 (D-176).
     * 호출자의 TX 하나 안에서 부르면 모든 페이지가 같은 read view 를 본다
     */
    public void readConfirmedBetween(LocalDate start, LocalDate end, int pageSize, Consumer<List<PurchaseInput>> pageConsumer) {
        long lastOrderId = 0;
        long lastProductId = 0;
        while (true) {
            List<PurchaseInput> page = jdbc.query(PAGE_SQL,
                (rs, rowNum) -> new PurchaseInput(
                    rs.getLong("order_id"),
                    rs.getLong("product_id"),
                    rs.getInt("quantity"),
                    rs.getObject("confirmed_at", LocalDateTime.class)
                ),
                lastOrderId, lastOrderId, lastProductId, start.atStartOfDay(), end.atStartOfDay(), pageSize);
            if (page.isEmpty()) {
                return;
            }
            pageConsumer.accept(page);
            PurchaseInput last = page.getLast();
            lastOrderId = last.orderId();
            lastProductId = last.productId();
        }
    }
}
