package com.loopers.application.ordering.facade;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.loopers.support.test.PurchaseActivityLab;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

record PurchaseActivityEvidence(List<Inbox> inboxes, List<Activity> activities, long failureCount) {
    record Inbox(long id, long orderId, UUID eventId, Instant confirmedAt) {}
    record Activity(long id, long inboxId, long orderId, long productId, int quantity, Instant confirmedAt) {}

    void assertMatches(Map<Long, Long> expectedProducts, List<PurchaseEventBroker.Delivery> deliveries, long teaId) {
        var orderIds = expectedProducts.keySet().stream().sorted().toList();
        assertThat(orderIds).hasSize(20);
        assertThat(deliveries).extracting(PurchaseEventBroker.Delivery::orderId).containsExactlyInAnyOrderElementsOf(orderIds);
        assertThat(inboxes).extracting(Inbox::orderId).containsExactlyElementsOf(orderIds);
        assertThat(activities).extracting(Activity::orderId).containsExactlyElementsOf(orderIds);
        assertThat(failureCount).as("소비 실패로 분류된 메시지").isZero();
        var byOrder = deliveries.stream().collect(Collectors.toMap(PurchaseEventBroker.Delivery::orderId, Function.identity()));
        assertThat(activities).zipSatisfy(inboxes, (activity, inbox) -> {
            var sent = byOrder.get(activity.orderId());
            assertThat(inbox.eventId()).isEqualTo(sent.eventId());
            assertThat(inbox.confirmedAt()).isEqualTo(sent.confirmedAt());
            assertThat(activity.inboxId()).isEqualTo(inbox.id());
            assertThat(activity.confirmedAt()).isEqualTo(sent.confirmedAt());
            assertThat(sent.productId()).isEqualTo(expectedProducts.get(activity.orderId()));
            assertThat(activity.productId()).isEqualTo(sent.productId());
            assertThat(sent.quantity()).isOne();
            assertThat(activity.quantity()).isOne();
        });
        assertThat(activities).filteredOn(row -> List.of(108L, 109L, 110L).contains(row.orderId()))
            .extracting(Activity::orderId, Activity::productId, Activity::quantity)
            .containsExactly(tuple(108L, teaId, 1), tuple(109L, teaId, 1), tuple(110L, teaId, 1));
    }

    static PurchaseActivityEvidence read(PurchaseActivityLab lab) throws SQLException {
        try (Connection connection = lab.connect()) {
            connection.setReadOnly(true);
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            connection.setAutoCommit(false);
            try (Statement query = connection.createStatement()) {
                var snapshot = new PurchaseActivityEvidence(readInbox(query), readActivities(query), readFailureCount(query));
                connection.commit();
                return snapshot;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        }
    }

    private static List<Inbox> readInbox(Statement query) throws SQLException {
        List<Inbox> result = new ArrayList<>();
        try (ResultSet rows = query.executeQuery("""
            SELECT id, order_id, event_id, confirmed_at
            FROM order_confirmation_inbox ORDER BY order_id, id
            """)) {
            while (rows.next()) {
                result.add(new Inbox(rows.getLong("id"), rows.getLong("order_id"),
                    UUID.fromString(rows.getString("event_id")), confirmedAt(rows)));
            }
        }
        return List.copyOf(result);
    }

    private static List<Activity> readActivities(Statement query) throws SQLException {
        List<Activity> result = new ArrayList<>();
        try (ResultSet rows = query.executeQuery("""
            SELECT id, inbox_id, order_id, product_id, quantity, confirmed_at
            FROM product_purchase_activities ORDER BY order_id, product_id, id
            """)) {
            while (rows.next()) {
                result.add(new Activity(rows.getLong("id"), rows.getLong("inbox_id"), rows.getLong("order_id"),
                    rows.getLong("product_id"), rows.getInt("quantity"), confirmedAt(rows)));
            }
        }
        return List.copyOf(result);
    }

    private static long readFailureCount(Statement query) throws SQLException {
        try (ResultSet count = query.executeQuery("SELECT COUNT(*) FROM order_event_failures")) {
            count.next();
            return count.getLong(1);
        }
    }

    private static Instant confirmedAt(ResultSet rows) throws SQLException {
        return rows.getObject("confirmed_at", LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
