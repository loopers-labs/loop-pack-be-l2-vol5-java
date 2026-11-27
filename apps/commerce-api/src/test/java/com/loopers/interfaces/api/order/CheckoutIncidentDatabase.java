package com.loopers.interfaces.api.order;

import com.loopers.application.coupon.CouponIssuanceService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Reads committed facts from the HTTP test's isolated database. Each scenario order has exactly one item. */
final class CheckoutIncidentDatabase {
    private final JdbcTemplate jdbc;
    private final NamedParameterJdbcTemplate named;

    CheckoutIncidentDatabase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        this.named = new NamedParameterJdbcTemplate(jdbc);
    }

    void seedOwnedCoupon(CouponIssuanceService issuance, long userId) {
        long campaignId = new SimpleJdbcInsert(jdbc).withTableName("coupon_campaign")
            .usingColumns("name", "benefit_description", "active", "total_quota", "waiting_count", "issued_count")
            .usingGeneratedKeyColumns("id")
            .executeAndReturnKey(Map.of("name", "W10 existing coupon", "benefit_description", "W10 test benefit",
                "active", true, "total_quota", 1, "waiting_count", 0, "issued_count", 0)).longValue();
        issuance.reserve(campaignId, userId);
        assertThat(issuance.issue(campaignId, userId).couponId()).isPositive();
    }

    Map<Long, OrderFact> orders(Collection<Long> orderIds) {
        assertThat(orderIds).isNotEmpty().doesNotHaveDuplicates();
        List<OrderFact> rows = named.query("""
            SELECT o.id AS order_id, o.status AS order_status, o.payment_method, o.total_amount, o.paid_amount,
                   o.confirmed_at, p.id AS attempt_id, BIN_TO_UUID(p.attempt_key) AS attempt_key,
                   p.provider_account, p.provider_order_id, p.transaction_key, p.amount, p.status AS payment_status,
                   p.send_started_at, i.id AS item_id, i.product_id, i.quantity, i.reservation_status,
                   b.id AS outbox_id, b.event_id, b.status AS outbox_status, SHA2(b.payload, 256) AS payload_hash
            FROM `order` o
            JOIN payment_attempt p ON p.order_id = o.id
            JOIN order_item i ON i.order_id = o.id
            LEFT JOIN outbox_message b ON b.aggregate_type = 'Order' AND b.aggregate_id = CAST(o.id AS CHAR)
            WHERE o.id IN (:ids)
            ORDER BY o.id, i.id, b.id
            """, Map.of("ids", orderIds), (row, index) -> new OrderFact(
                row.getLong("order_id"), row.getString("order_status"), row.getString("payment_method"),
                row.getLong("total_amount"), row.getObject("paid_amount", Long.class), row.getString("confirmed_at"),
                row.getLong("attempt_id"), UUID.fromString(row.getString("attempt_key")), row.getString("provider_account"),
                row.getString("provider_order_id"), row.getString("transaction_key"), row.getLong("amount"),
                row.getString("payment_status"), row.getString("send_started_at"), row.getLong("item_id"),
                row.getLong("product_id"), row.getInt("quantity"), row.getString("reservation_status"),
                row.getObject("outbox_id", Long.class), row.getString("event_id"), row.getString("outbox_status"),
                row.getString("payload_hash")));
        assertThat(rows).extracting(OrderFact::orderId).containsExactlyInAnyOrderElementsOf(orderIds);
        Map<Long, OrderFact> result = new LinkedHashMap<>();
        rows.forEach(row -> result.put(row.orderId(), row));
        return Collections.unmodifiableMap(result);
    }

    Set<Long> unknownOrders(Collection<Long> orderIds) {
        assertThat(orderIds).isNotEmpty().doesNotHaveDuplicates();
        List<Long> unknown = named.queryForList("""
            SELECT order_id FROM payment_attempt
            WHERE status = 'UNKNOWN' AND order_id IN (:ids) ORDER BY order_id
            """, Map.of("ids", orderIds), Long.class);
        assertThat(unknown).doesNotHaveDuplicates();
        return new LinkedHashSet<>(unknown);
    }

    int availableStock(long productId) {
        return jdbc.queryForObject("SELECT stock_quantity FROM product WHERE id=?", Integer.class, productId);
    }

    long outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_message", Long.class);
    }

    Safeguards safeguards() {
        var balances = jdbc.queryForList("SELECT id, point_balance FROM `user` ORDER BY id");
        var campaigns = jdbc.queryForList("""
            SELECT id, name, active + 0 AS active, total_quota, waiting_count, issued_count, benefit_description
            FROM coupon_campaign ORDER BY id
            """);
        var reservations = jdbc.queryForList("SELECT id, campaign_id, user_id, status FROM coupon_reservation ORDER BY id");
        var coupons = jdbc.queryForList("""
            SELECT id, reservation_id, benefit_description, CAST(issued_at AS CHAR) AS issued_at
            FROM owned_coupon ORDER BY id
            """);
        assertThat(balances).anySatisfy(row -> assertThat(((Number) row.get("point_balance")).longValue()).isPositive());
        assertThat(coupons).isNotEmpty();
        return new Safeguards(balances, campaigns, reservations, coupons);
    }

    record Safeguards(List<Map<String, Object>> balances, List<Map<String, Object>> campaigns,
                      List<Map<String, Object>> reservations, List<Map<String, Object>> coupons) { }

    record OrderFact(long orderId, String orderStatus, String paymentMethod, long totalAmount, Long paidAmount,
                     String confirmedAt, long attemptId, UUID attemptKey, String providerAccount, String providerOrderId,
                     String transactionKey, long amount, String paymentStatus, String sendStartedAt, long itemId,
                     long productId, int quantity, String reservationStatus, Long outboxId, String eventId,
                     String outboxStatus, String payloadHash) {
        void assertSameOriginalAttemptAndItem(OrderFact original) {
            assertThat(orderId).isEqualTo(original.orderId);
            assertThat(attemptId).isEqualTo(original.attemptId);
            assertThat(attemptKey).isEqualTo(original.attemptKey);
            assertThat(providerAccount).isEqualTo(original.providerAccount);
            assertThat(providerOrderId).isEqualTo(original.providerOrderId);
            assertThat(sendStartedAt).isEqualTo(original.sendStartedAt);
            assertThat(totalAmount).isEqualTo(original.totalAmount);
            assertThat(amount).isEqualTo(original.amount);
            assertThat(itemId).isEqualTo(original.itemId);
            assertThat(productId).isEqualTo(original.productId);
            assertThat(quantity).isEqualTo(original.quantity);
        }

        void assertConfirmed(long expectedProduct, long expectedAmount) {
            assertPurchase(expectedProduct, expectedAmount);
            assertThat(orderStatus).isEqualTo("CONFIRMED");
            assertThat(paymentStatus).isEqualTo("SUCCESS");
            assertThat(reservationStatus).isEqualTo("CONSUMED");
            assertThat(paidAmount).isEqualTo(expectedAmount);
            assertThat(confirmedAt).isNotBlank();
            assertThat(transactionKey).isNotBlank();
            assertThat(outboxId).isPositive();
            assertThat(eventId).isEqualTo("order-confirmed:v1:" + orderId);
            assertThat(outboxStatus).isEqualTo("PENDING");
            assertThat(payloadHash).hasSize(64);
        }

        void assertUnknown(long expectedProduct, long expectedAmount) {
            assertPurchase(expectedProduct, expectedAmount);
            assertThat(orderStatus).isEqualTo("PAYMENT_PENDING");
            assertThat(paymentStatus).isEqualTo("UNKNOWN");
            assertThat(reservationStatus).isEqualTo("HELD");
            assertThat(paidAmount).isNull();
            assertThat(confirmedAt).isNull();
            assertThat(transactionKey).isNull();
            assertThat(outboxId).isNull();
        }

        private void assertPurchase(long expectedProduct, long expectedAmount) {
            assertThat(paymentMethod).isEqualTo("PG");
            assertThat(providerAccount).isEqualTo("135135");
            assertThat(providerOrderId).isNotBlank();
            assertThat(sendStartedAt).isNotBlank();
            assertThat(amount).isEqualTo(expectedAmount);
            assertThat(totalAmount).isEqualTo(expectedAmount);
            assertThat(productId).isEqualTo(expectedProduct);
            assertThat(quantity).isEqualTo(1);
        }
    }
}
