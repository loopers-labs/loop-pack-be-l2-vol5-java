package com.loopers.application.payment;

import com.loopers.domain.common.Money;
import com.loopers.domain.order.OrderItemModel;
import com.loopers.domain.order.OrderModel;
import com.loopers.domain.product.ProductModel;
import com.loopers.fixture.ProductFixture;
import com.loopers.infrastructure.order.OrderJpaRepository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 결제 TX 통합 테스트의 준비·관측. 결과는 commit 뒤 새 연결(JdbcTemplate)로 읽어 같은 Entity·영속성 컨텍스트를 재사용하지 않는다.
 */
@Component
public class PaymentTxFixture {

    static final String KEY_A = "123e4567-e89b-12d3-a456-426614174000";
    static final String KEY_B = "123e4567-e89b-12d3-a456-426614174001";

    private final ProductFixture productFixture;
    private final OrderJpaRepository orderJpaRepository;
    private final JdbcTemplate jdbcTemplate;

    public PaymentTxFixture(ProductFixture productFixture, OrderJpaRepository orderJpaRepository, JdbcTemplate jdbcTemplate) {
        this.productFixture = productFixture;
        this.orderJpaRepository = orderJpaRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    public ProductModel product(long price, long stock) {
        return productFixture.createProduct("상품", price, stock);
    }

    /** 각 상품을 quantity 만큼 담은 DRAFT 주문. 단가는 상품 가격이다. */
    public Long draftOrder(Long userId, long quantity, ProductModel... products) {
        List<OrderItemModel> items = new ArrayList<>();
        for (ProductModel product : products) {
            items.add(OrderItemModel.of(product.getId(), quantity, product.getPrice()));
        }
        return orderJpaRepository.save(OrderModel.draft(userId, items)).getId();
    }

    public static PaymentConfirmCommand command(String key) {
        return new PaymentConfirmCommand(key, "TEST", "0000-0000-0000-0000");
    }

    public Map<String, Object> order(Long orderId) {
        return jdbcTemplate.queryForMap(
            "SELECT status, payment_method, point_amount, paid_amount FROM orders WHERE id = ?", orderId);
    }

    public Map<String, Object> payment(Long orderId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            SELECT id, status, failure_kind, transaction_key, verification_status, verification_checked_at, amount
            FROM payment WHERE order_id = ?
            """, orderId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    public long paymentCount(Long orderId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Long.class, orderId);
    }

    public List<Map<String, Object>> conflicts(Long orderId) {
        return jdbcTemplate.queryForList("""
            SELECT c.code, c.observed_order_id, c.observed_transaction_key, c.observed_amount, c.observed_status, c.observed_count
            FROM payment_conflict c JOIN payment p ON p.id = c.payment_id WHERE p.order_id = ?
            """, orderId);
    }

    public long stock(Long productId) {
        return jdbcTemplate.queryForObject("SELECT stock_quantity FROM product WHERE id = ?", Long.class, productId);
    }

    public long histories(Long orderId, String cause) {
        return jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM stock_history WHERE order_id = ? AND cause = ?", Long.class, orderId, cause);
    }

    /**
     * 같은 주문 행의 Order 잠금을 기다리는 TX 가 있는지 InnoDB 로 본다. 테스트 DB 사용자에게 PROCESS 권한이 없어
     * 테스트 컨테이너의 root 로 따로 연결한다(테스트 전용 관측).
     */
    public boolean someoneWaitsForOrderLock() {
        String url = System.getProperty("datasource.mysql-jpa.main.jdbc-url");
        String password = System.getProperty("datasource.mysql-jpa.main.password");
        try (Connection connection = DriverManager.getConnection(url, "root", password);
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("""
                 SELECT COUNT(*) FROM information_schema.innodb_trx
                 WHERE trx_state = 'LOCK WAIT' AND LOWER(trx_query) LIKE '%from orders%for update%'
                 """)) {
            rs.next();
            return rs.getLong(1) > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("잠금 대기 관측 실패", e);
        }
    }

    public Money price(ProductModel product) {
        return product.getPrice();
    }
}
