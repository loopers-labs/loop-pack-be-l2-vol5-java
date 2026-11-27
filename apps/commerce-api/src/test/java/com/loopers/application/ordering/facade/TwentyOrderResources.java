package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;

record TwentyOrderResources(long userId, long teaId, long coffeeId, int teaStock, int coffeeStock,
                           List<Map<String, Object>> wallets, List<Map<String, Object>> pointBills,
                           List<Map<String, Object>> coupons) {
    static TwentyOrderResources read(JdbcTemplate db, long userId, long teaId, long coffeeId) {
        String stock = "SELECT stock FROM products WHERE id = ?";
        return new TwentyOrderResources(userId, teaId, coffeeId,
            db.queryForObject(stock, Integer.class, teaId), db.queryForObject(stock, Integer.class, coffeeId),
            db.queryForList("SELECT user_id, balance FROM wallets WHERE user_id = ?", userId),
            db.queryForList("SELECT id, user_id, order_id, type, amount, created_at FROM point_bills WHERE user_id = ? ORDER BY id", userId),
            db.queryForList("""
                SELECT id, campaign_id, slot_id, user_id, benefit_description, discount_amount, issued_at, created_at, updated_at
                  FROM coupons WHERE user_id = ? ORDER BY id
                """, userId));
    }

    void assertAfter(TwentyOrderResources before) {
        assertThat(userId).isEqualTo(before.userId());
        assertThat(teaId).isEqualTo(before.teaId());
        assertThat(coffeeId).isEqualTo(before.coffeeId());
        assertThat(before.teaStock()).isEqualTo(20);
        assertThat(before.coffeeStock()).isEqualTo(20);
        assertThat(teaStock).as("차 주문 11건 처리 후 재고").isEqualTo(before.teaStock() - 11);
        assertThat(coffeeStock).as("커피 주문 9건 처리 후 재고").isEqualTo(before.coffeeStock() - 9);
        assertThat(wallets).as("포인트 지갑과 잔액").isEqualTo(before.wallets());
        assertThat(pointBills).as("포인트 적립·사용 내역").isEqualTo(before.pointBills());
        assertThat(coupons).as("주문자 보유 쿠폰").isEqualTo(before.coupons());
    }
}
