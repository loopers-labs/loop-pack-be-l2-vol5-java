package com.loopers.interfaces.api.coupon;

import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.testcontainers.containers.MySQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class CouponLoadDatabase {
    private static final List<String> SCHEMA = List.of("001-brand.sql", "002-user.sql", "003-product.sql",
        "004-like.sql", "005-order.sql", "006-order-item.sql", "007-product-brand-price-index.sql",
        "008-product-list-revision.sql", "009-order-payment-state.sql", "010-payment-attempt.sql",
        "011-payment-request-fingerprint.sql", "012-outbox.sql", "013-coupon-issuance.sql", "014-coupon-waiting-index.sql");

    private CouponLoadDatabase() {
    }

    static JdbcTemplate seed(MySQLContainer<?> mysql, long campaignId, int applicants, int quota) {
        var source = new DriverManagerDataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        var schema = new ResourceDatabasePopulator();
        SCHEMA.forEach(script -> schema.addScript(new ClassPathResource("db/schema/" + script)));
        schema.execute(source);
        var db = new JdbcTemplate(source);
        // Generate all applicant PKs in MySQL before any measurement. Each CTE stays below the default recursion limit.
        int users = db.update("""
            INSERT INTO `user` (id, point_balance, created_at, updated_at)
            WITH RECURSIVE offsets(n) AS (
                SELECT 0 UNION ALL SELECT n + 1 FROM offsets WHERE n < 999
            ), batches(n) AS (
                SELECT 0 UNION ALL SELECT n + 1 FROM batches WHERE (n + 1) * 1000 < ?
            )
            SELECT b.n * 1000 + o.n + 1, 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6)
            FROM batches b CROSS JOIN offsets o WHERE b.n * 1000 + o.n < ?
            """, applicants, applicants);
        if (users != applicants) {
            throw new IllegalStateException("Seeded " + users + " applicants, expected " + applicants);
        }
        db.update("INSERT INTO brand (id, name, created_at, updated_at) VALUES (1, 'measurement brand', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");
        db.update("""
            INSERT INTO product (id, brand_id, name, price, stock_quantity, created_at, updated_at)
            VALUES (1, 1, 'measurement product', 1000, 5, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """);
        db.update("""
            INSERT INTO `order` (id, user_id, status, total_amount, created_at, updated_at)
            VALUES (1, 1, 'DRAFT', 1000, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
            """);
        db.update("""
            INSERT INTO order_item (id, order_id, product_id, product_name_snapshot, unit_price_snapshot, quantity)
            VALUES (1, 1, 1, 'measurement product', 1000, 1)
            """);
        CouponHttpIntegrationFixture.insertCampaign(db, campaignId, quota);
        return db;
    }

    record State(int totalQuota, int waitingCount, int issuedCount, int reservations, int reservedUsers,
                 int issuedReservations, int ownedCoupons, int owners) {
    }

    static State state(JdbcTemplate db, long campaignId) {
        return db.queryForObject("""
            SELECT c.total_quota, c.waiting_count, c.issued_count,
                   COUNT(r.id) AS reservations, COUNT(DISTINCT r.user_id) AS reserved_users,
                   COUNT(CASE WHEN r.status = 'ISSUED' THEN 1 END) AS issued_reservations,
                   COUNT(o.id) AS owned_coupons, COUNT(DISTINCT CASE WHEN o.id IS NOT NULL THEN r.user_id END) AS owners
            FROM coupon_campaign c LEFT JOIN coupon_reservation r ON r.campaign_id = c.id
              LEFT JOIN owned_coupon o ON o.reservation_id = r.id
            WHERE c.id = ? GROUP BY c.id
            """, (rs, row) -> new State(rs.getInt("total_quota"), rs.getInt("waiting_count"), rs.getInt("issued_count"),
                rs.getInt("reservations"), rs.getInt("reserved_users"), rs.getInt("issued_reservations"),
                rs.getInt("owned_coupons"), rs.getInt("owners")), campaignId);
    }

    static Map<Long, Long> winners(JdbcTemplate db, long campaignId) {
        Map<Long, Long> winners = new HashMap<>();
        db.query("""
            SELECT r.user_id, r.id
            FROM coupon_reservation r JOIN owned_coupon o ON o.reservation_id = r.id
            WHERE r.campaign_id = ? AND r.status = 'ISSUED'
            """, (RowCallbackHandler) row -> winners.put(row.getLong("user_id"), row.getLong("id")), campaignId);
        return Map.copyOf(winners);
    }
}
